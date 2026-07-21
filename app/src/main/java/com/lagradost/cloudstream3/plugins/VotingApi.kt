package com.lagradost.cloudstream3.plugins

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Voting backed by the fire-backend ICP canister (see fire-backend/CLAUDE.md).
 *
 * The canister exposes two Candid endpoints, reached over the IC HTTP gateway:
 *   - `get_score : (text) -> (opt float64) query`  -> TrustScore % (0..100) or null ("New")
 *   - `vote : (text, variant { Up; Down }) -> (Result)` update
 *
 * We call as the **anonymous** principal, so update calls need no signature and
 * no read_state/certificate handling: POST the CBOR envelope, treat 2xx as
 * accepted, then re-query the (free) `get_score`. Candid + CBOR are hand-encoded
 * below — the app has no IC agent library.
 *
 * Both up- and down-votes are cast from this client (thumb-up / thumb-down UI);
 * the canister tracks up/down and returns the combined Wilson TrustScore, which
 * `get_score` exposes as a percentage (0..100) — never a raw vote count. The UI
 * therefore shows that TrustScore %, not a number of upvotes (no count endpoint
 * exists; see fire-backend/CLAUDE.md "Non-goals").
 *
 * The anonymous principal is shared by all unauthenticated callers, so the
 * backend deliberately does *not* key a record on it: every anonymous call is an
 * independent ballot that only adds to the subject's accumulator. (It used to
 * store one record per subject, which made the entire anonymous population count
 * as a single voter — the 2nd anon vote hit the 1st one's record and either
 * no-op'd or overwrote it.)
 *
 * An anonymous ballot is consequently write-only: it cannot be de-duplicated or
 * retracted, because there is nothing to identify it by. Only an identified
 * (non-anonymous) caller gets a switchable record. This is the accepted
 * trade-off, not a gap to work around — see fire-backend/CLAUDE.md, "Two ballot
 * kinds".
 *
 * So the stored direction per subject here is **local UI state**, not a mirror
 * of backend state. It drives which thumb renders as selected and suppresses a
 * same-direction re-cast client-side (that call never reaches the canister).
 * A flip does reach the canister, and lands as an additional opposing ballot
 * rather than replacing the first: one user going up -> down leaves the subject
 * holding one up and one down (~50%). Intended — the canister is counting
 * ballots, and it has no way to know those two came from the same person.
 */
object VotingApi {
    private const val LOGKEY = "VotingApi"

    // --- Deployment config -------------------------------------------------
    // fire-backend runs on a local/LAN dfx replica (see scripts/deploy-lan.sh).
    // Fill these after `dfx deploy fire_backend`:
    //   REPLICA_URL  = http://<lan-host-ip>:4943   (no trailing slash)
    //   CANISTER_ID  = output of `dfx canister id fire_backend`
    private const val REPLICA_URL = "" //http://192.168.91.130:4943
    private const val CANISTER_ID = "uxrrr-q7777-77774-qaaaq-cai"

    /**
     * Master switch for the whole voting feature.
     *
     * Off while fire-backend has no permanent home: it only ever ran on a LAN
     * dfx replica, so every install that is not on that network gets failed
     * calls and an empty TrustScore. The implementation below is kept intact
     * and is expected to come back — flip this to true once the canister is
     * deployed somewhere reachable and [REPLICA_URL] is filled in.
     *
     * The UI reads this too, hiding the thumbs and the flame badge rather than
     * showing controls that cannot do anything.
     */
    const val ENABLED = false

    private val CBOR = "application/cbor".toMediaType()

    // REPLICA_URL is part of the check: an empty host is as unusable as a
    // missing canister id, and both are how the feature is currently parked.
    private val configured
        get() = ENABLED && REPLICA_URL.isNotBlank() && CANISTER_ID.isNotBlank()

    // Post-vote re-read retry: covers the brief window where a get_score query
    // can still race the just-committed update and read the pre-vote state.
    private const val RETRY_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 400L

    private fun transformUrl(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${url}#funny-salt".toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }

    suspend fun SitePlugin.getScore(): Double? = getScore(url)
    fun SitePlugin.hasVoted(): Boolean = hasVoted(url)
    /** null = not voted, true = up, false = down. */
    fun SitePlugin.votedDirection(): Boolean? = votedDirection(url)
    suspend fun SitePlugin.vote(up: Boolean): Double? = vote(url, up)

    // --- Score cache -------------------------------------------------------
    // Two layers, mirroring how the rest of the app caches: an in-memory map
    // in front of the DataStore (setKey/getKey) SharedPreferences store.
    // Disk is what lets a cold start paint scores before any network call;
    // memory keeps list binds off SharedPreferences and JSON parsing.
    //
    // Reads are stale-while-revalidate: `peekScores` returns whatever is on
    // disk however old, so the list renders at once, and `getScores` then
    // refreshes anything past the TTL.

    /** Persisted cache entry. `score` null = the canister answered "New". */
    @Serializable
    data class CachedScore(
        @JsonProperty("score") @SerialName("score") val score: Double?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: Long,
    )

    private const val SCORE_CACHE_FOLDER = "cs3-score-cache"
    private const val SCORE_TTL_MS = 6L * 60L * 60L * 1000L

    /**
     * Cap on simultaneous `get_score` queries. A repository can list 50+
     * plugins and firing that many sockets at once starves the connection
     * pool and stalls icon loading in the same list.
     */
    private const val SCORE_CONCURRENCY = 8

    private val scoreCache = ConcurrentHashMap<String, CachedScore>()

    /** Shared by `refresh` so one fetch is not tied to a single caller's scope. */
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLock = Mutex()
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<Double?>>>()

    private fun scoreKey(pluginUrl: String) =
        "$SCORE_CACHE_FOLDER/${transformUrl(pluginUrl)}"

    private fun CachedScore.isFresh() =
        System.currentTimeMillis() - updatedAt < SCORE_TTL_MS

    /** Memory, falling back to disk (promoted into memory on a hit). */
    private fun cached(pluginUrl: String): CachedScore? =
        scoreCache[pluginUrl] ?: getKey<CachedScore>(scoreKey(pluginUrl))?.also {
            scoreCache[pluginUrl] = it
        }

    private fun store(pluginUrl: String, score: Double?) {
        val entry = CachedScore(score, System.currentTimeMillis())
        scoreCache[pluginUrl] = entry
        setKey(scoreKey(pluginUrl), entry)
    }

    /**
     * Fetch and cache, collapsing concurrent callers for the same URL onto one
     * query. Failure is distinct from a null score so a network blip is never
     * cached as "New".
     */
    private suspend fun refresh(pluginUrl: String): Result<Double?> {
        val job = inFlightLock.withLock {
            inFlight[pluginUrl] ?: scoreScope.async {
                readScore(pluginUrl).onSuccess { store(pluginUrl, it) }
            }.also { started ->
                inFlight[pluginUrl] = started
                // The entry has to be cleared by the fetch finishing, not by a
                // caller leaving: a caller cancelled mid-await cannot run a
                // suspending cleanup, which would strand the finished Deferred
                // here and serve its value forever in place of the TTL. Fires
                // inline if the job is already done, so it cannot leak either.
                started.invokeOnCompletion { inFlight.remove(pluginUrl, started) }
            }
        }
        // Awaiting a job owned by scoreScope: this caller being cancelled must
        // not cancel the fetch that other callers are sharing.
        return job.await()
    }

    suspend fun getScore(pluginUrl: String): Double? {
        cached(pluginUrl)?.let { if (it.isFresh()) return it.score }
        return refresh(pluginUrl).getOrElse { cached(pluginUrl)?.score }
    }

    /**
     * Cached scores only, no network and no TTL check — for painting a list
     * immediately. Absent keys are simply unknown.
     */
    suspend fun peekScores(pluginUrls: List<String>): Map<String, Double?> =
        withContext(Dispatchers.IO) {
            pluginUrls.distinct().mapNotNull { url ->
                cached(url)?.let { url to it.score }
            }.toMap()
        }

    /**
     * Scores for a whole list: cached-and-fresh entries cost nothing, the rest
     * are queried concurrently up to [SCORE_CONCURRENCY]. A URL whose refresh
     * fails falls back to its stale cached value, and is omitted entirely if
     * there is none.
     */
    suspend fun getScores(pluginUrls: List<String>): Map<String, Double?> =
        withContext(Dispatchers.IO) {
            val gate = Semaphore(SCORE_CONCURRENCY)
            pluginUrls.distinct().amap { url ->
                val hit = cached(url)
                if (hit != null && hit.isFresh()) {
                    url to hit.score
                } else {
                    val result = gate.withPermit { refresh(url) }
                    url to result.getOrElse { hit?.score ?: return@amap null }
                }
            }.filterNotNull().toMap()
        }

    // stored value per subject: true = up, false = down, absent = not voted.
    fun votedDirection(pluginUrl: String): Boolean? =
        getKey<Boolean>("cs3-votes/${transformUrl(pluginUrl)}")

    fun hasVoted(pluginUrl: String): Boolean = votedDirection(pluginUrl) != null

    private val voteLock = Mutex()

    // Voting is allowed on any extension, installed or not: the subject is the
    // plugin's URL, which exists in the repository list before download.
    suspend fun vote(pluginUrl: String, up: Boolean): Double? {
        voteLock.withLock {
            // Same direction already cast → no-op. This guard is the only thing
            // making a re-cast idempotent: an anonymous ballot is write-only on
            // the canister side, so a second identical call would be counted
            // again rather than recognised as a repeat.
            if (votedDirection(pluginUrl) == up) {
                main {
                    Toast.makeText(context, R.string.already_voted, Toast.LENGTH_SHORT).show()
                }
                return getScore(pluginUrl)
            }

            // New vote, or the user flipping their thumb. Both are sent as a
            // plain ballot: the canister adds it to the subject's accumulator,
            // and a flip therefore offsets the earlier ballot rather than
            // replacing it. The stored key below tracks the UI selection only.
            if (castVote(pluginUrl, up)) {
                setKey("cs3-votes/${transformUrl(pluginUrl)}", up)
                // The vote just committed, so a score now exists. A query can
                // still momentarily race the commit and read the pre-vote state
                // (null); re-read with a short backoff and cache only the
                // settled value, never a stale null. Caching null here would
                // pin "No data" until the TTL expires.
                return readScoreSettled(pluginUrl)?.also { store(pluginUrl, it) }
            }

            return getScore(pluginUrl)
        }
    }

    // --- Canister calls ----------------------------------------------------

    /**
     * Query `get_score(subject)` -> TrustScore % (0..100) or null ("New").
     *
     * Success means the canister answered; the value inside may still be null
     * ("New"). Failure means the call did not complete, which callers must not
     * confuse with "New" — only a success is worth caching.
     */
    private suspend fun readScore(pluginUrl: String): Result<Double?> {
        if (!configured) {
            Log.w(LOGKEY, "voting disabled or unconfigured; skipping get_score")
            return Result.failure(IllegalStateException("voting disabled or unconfigured"))
        }
        val subject = transformUrl(pluginUrl)
        val envelope = queryEnvelope("get_score", candidTextArg(subject))
        val reply = post("v2", "query", envelope)
            ?: return Result.failure(IOException("get_score call failed"))
        val arg = replyArg(reply)
            ?: return Result.failure(IOException("get_score returned no reply arg"))
        return Result.success(decodeOptFloat64(arg))
    }

    /**
     * `readScore` retried against the commit-vs-query race after a vote. We only
     * call this right after casting, when a score is guaranteed to exist, so a
     * null means "not committed yet" — retry a few times before giving up.
     */
    private suspend fun readScoreSettled(pluginUrl: String): Double? {
        repeat(RETRY_ATTEMPTS) { i ->
            readScore(pluginUrl).getOrNull()?.let { return it }
            if (i < RETRY_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    /** Update `vote(subject, dir)`. Returns true if the replica accepted it. */
    private suspend fun castVote(pluginUrl: String, up: Boolean): Boolean {
        if (!configured) {
            Log.w(LOGKEY, "voting disabled or unconfigured; cannot vote")
            return false
        }
        val subject = transformUrl(pluginUrl)
        val envelope = callEnvelope("vote", candidVoteArg(subject, up))
        // Anonymous update call via the v3 *synchronous* endpoint: the replica
        // holds the response until the call is executed and returns the
        // certified reply (200), instead of the v2 endpoint's fire-and-forget
        // 202 that returns before the vote commits. This makes the subsequent
        // get_score re-query see the committed state. 2xx = accepted; the
        // readScoreSettled retry covers the rare 202 fallback under load.
        return post("v3", "call", envelope) != null
    }

    private suspend fun post(apiVersion: String, endpoint: String, body: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = "$REPLICA_URL/api/$apiVersion/canister/$CANISTER_ID/$endpoint"
            Log.d(LOGKEY, "POST $url (${body.size} B cbor)")
            try {
                val req = Request.Builder().url(url).post(body.toRequestBody(CBOR)).build()
                app.baseClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(LOGKEY, "canister call failed: HTTP ${resp.code}")
                        return@withContext null
                    }
                    resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (t: Throwable) {
                Log.e(LOGKEY, "canister call error: ${Log.getStackTraceString(t)}")
                null
            }
        }

    // --- IC request envelopes (CBOR) --------------------------------------

    private const val ANONYMOUS = 0x04.toByte()
    private val secureRandom = SecureRandom()

    /** ns since epoch + ~4 min, under the IC's 5-minute ingress-expiry cap. */
    private fun ingressExpiry(): Long =
        System.currentTimeMillis() * 1_000_000L + 4L * 60L * 1_000_000_000L

    private fun queryEnvelope(method: String, arg: ByteArray): ByteArray =
        Cbor().apply {
            beginMap(1)
            text("content")
            beginMap(6)
            text("request_type"); text("query")
            text("sender"); bytes(byteArrayOf(ANONYMOUS))
            text("canister_id"); bytes(principalRaw(CANISTER_ID))
            text("method_name"); text(method)
            text("arg"); bytes(arg)
            text("ingress_expiry"); uint(ingressExpiry())
        }.toByteArray()

    private fun callEnvelope(method: String, arg: ByteArray): ByteArray {
        val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }
        return Cbor().apply {
            beginMap(1)
            text("content")
            beginMap(7)
            text("request_type"); text("call")
            text("sender"); bytes(byteArrayOf(ANONYMOUS))
            text("canister_id"); bytes(principalRaw(CANISTER_ID))
            text("method_name"); text(method)
            text("arg"); bytes(arg)
            text("ingress_expiry"); uint(ingressExpiry())
            text("nonce"); bytes(nonce)
        }.toByteArray()
    }

    /** Extract `reply.arg` from a `{status:"replied", reply:{arg:...}}` envelope. */
    @Suppress("UNCHECKED_CAST")
    private fun replyArg(cbor: ByteArray): ByteArray? {
        val root = CborReader(cbor).read() as? Map<String, Any?> ?: return null
        if (root["status"] != "replied") {
            Log.w(LOGKEY, "canister status=${root["status"]} msg=${root["reject_message"]}")
            return null
        }
        val reply = root["reply"] as? Map<String, Any?> ?: return null
        return reply["arg"] as? ByteArray
    }

    // --- Candid argument encoding -----------------------------------------

    /** Encode `(text)`. */
    private fun candidTextArg(s: String): ByteArray = ByteArrayOutputStream().apply {
        write("DIDL".toByteArray(Charsets.US_ASCII))
        writeByte(0x00)          // 0 type-table entries
        writeByte(0x01)          // 1 argument
        writeByte(0x71)          // text
        val utf8 = s.toByteArray()
        writeLeb128(utf8.size.toLong())
        write(utf8)
    }.toByteArray()

    /** Encode `(text, variant { Up; Down })`. */
    private fun candidVoteArg(s: String, up: Boolean): ByteArray {
        // Field ids are the Candid label hashes; fields go in ascending-hash order.
        val hUp = candidHash("Up")
        val hDown = candidHash("Down")
        // Sorted order and the selected variant's index within it.
        val ascending = listOf(hUp to "Up", hDown to "Down").sortedBy { it.first }
        val selectedHash = if (up) hUp else hDown
        val selectedIdx = ascending.indexOfFirst { it.first == selectedHash }.toLong()

        return ByteArrayOutputStream().apply {
            write("DIDL".toByteArray(Charsets.US_ASCII))
            // --- type table: 1 entry (the variant) ---
            writeByte(0x01)
            writeByte(0x6b)                      // variant (sleb128 -21)
            writeLeb128(2)                       // 2 fields
            for ((hash, _) in ascending) {
                writeLeb128(hash)                // field id (hash)
                writeByte(0x7f)                  // field type: null (no payload)
            }
            // --- argument types ---
            writeByte(0x02)                      // 2 arguments
            writeByte(0x71)                      // arg0: text
            writeByte(0x00)                      // arg1: type table index 0 (the variant)
            // --- values ---
            val utf8 = s.toByteArray()
            writeLeb128(utf8.size.toLong())      // text length
            write(utf8)                          // text bytes
            writeLeb128(selectedIdx)             // chosen variant index
        }.toByteArray()
    }

    /** Candid label hash: fold h = h*223 + byte (mod 2^32). */
    private fun candidHash(name: String): Long =
        name.toByteArray(Charsets.US_ASCII).fold(0L) { h, b ->
            (h * 223L + (b.toInt() and 0xff)) and 0xffff_ffffL
        }

    // --- Candid reply decoding: `opt float64` -----------------------------

    private fun decodeOptFloat64(arg: ByteArray): Double? {
        val c = CandidCursor(arg)
        // magic
        repeat(4) { c.byte() } // "DIDL"
        // type table — parse structurally to advance the cursor
        val entries = c.leb128()
        repeat(entries.toInt()) { c.skipTypeDef() }
        // argument types
        val args = c.leb128()
        repeat(args.toInt()) { c.sleb128() }
        // value: opt flag, then f64 little-endian if present
        return if (c.byte().toInt() == 0) null else c.f64()
    }

    // --- Principal (textual) -> raw bytes ---------------------------------

    /** Decode a textual principal to its raw bytes (strips the 4-byte CRC32). */
    private fun principalRaw(text: String): ByteArray {
        val decoded = base32Decode(text.replace("-", ""))
        return decoded.copyOfRange(4, decoded.size)
    }

    private fun base32Decode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s.lowercase()) {
            val v = when (ch) {
                in 'a'..'z' -> ch - 'a'
                in '2'..'7' -> ch - '2' + 26
                else -> continue
            }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

private fun ByteArrayOutputStream.writeByte(b: Int) = write(b and 0xff)

/** Unsigned LEB128 (Candid lengths, field ids, indices). */
private fun ByteArrayOutputStream.writeLeb128(value: Long) {
    var v = value
    do {
        var b = (v and 0x7f).toInt()
        v = v ushr 7
        if (v != 0L) b = b or 0x80
        write(b)
    } while (v != 0L)
}

// ---------------------------------------------------------------------------
// Minimal CBOR writer (unsigned/text/bytes/map/array only — all the IC needs).
// ---------------------------------------------------------------------------

private class Cbor {
    private val out = ByteArrayOutputStream()

    fun beginMap(n: Int) = head(5, n.toLong())
    fun beginArray(n: Int) = head(4, n.toLong())
    fun uint(v: Long) = head(0, v)
    fun text(s: String) {
        val b = s.toByteArray()
        head(3, b.size.toLong()); out.write(b)
    }
    fun bytes(b: ByteArray) {
        head(2, b.size.toLong()); out.write(b)
    }

    private fun head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> out.write(m or value.toInt())
            value < 0x100 -> { out.write(m or 24); out.write(value.toInt()) }
            value < 0x10000 -> { out.write(m or 25); writeBE(value, 2) }
            value < 0x1_0000_0000L -> { out.write(m or 26); writeBE(value, 4) }
            else -> { out.write(m or 27); writeBE(value, 8) }
        }
    }

    private fun writeBE(value: Long, n: Int) {
        for (i in n - 1 downTo 0) out.write(((value ushr (8 * i)) and 0xff).toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

// ---------------------------------------------------------------------------
// Minimal CBOR reader -> Map / List / ByteArray / String / Long.
// ---------------------------------------------------------------------------

/** Sentinel for the CBOR "break" stop code (0xff) ending an indefinite-length item. */
private object Break

private class CborReader(private val buf: ByteArray) {
    private var pos = 0

    fun read(): Any? {
        val b = next().toInt() and 0xff
        val major = b ushr 5
        val info = b and 0x1f
        return when (major) {
            0 -> length(info)                       // unsigned int
            2 -> readBytes(length(info).toInt())    // byte string
            3 -> String(readBytes(length(info).toInt()), Charsets.UTF_8) // text
            4 -> if (info == 31)                     // array, indefinite length
                buildList { while (true) { val v = read(); if (v === Break) break; add(v) } }
            else List(length(info).toInt()) { read() }                   // array, definite
            5 -> LinkedHashMap<String, Any?>().apply {                   // map
                if (info == 31) {                    // indefinite length: read pairs until break.
                    while (true) {
                        val k = read(); if (k === Break) break
                        put(k.toString(), read())
                    }
                } else repeat(length(info).toInt()) { put(read().toString(), read()) }
            }
            6 -> { length(info); read() }           // tag: consume tag arg (e.g. IC self-describe 55799 = d9 d9 f7), then payload
            7 -> when (info) {                       // simple/float
                31 -> Break                          // break stop code for indefinite items
                22, 23 -> null                       // null / undefined
                else -> length(info)
            }
            else -> null
        }
    }

    private fun length(info: Int): Long = when (info) {
        in 0..23 -> info.toLong()
        24 -> readBE(1)
        25 -> readBE(2)
        26 -> readBE(4)
        27 -> readBE(8)
        else -> 0
    }

    private fun readBE(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 8) or (next().toLong() and 0xff) }
        return v
    }

    private fun readBytes(n: Int): ByteArray =
        buf.copyOfRange(pos, pos + n).also { pos += n }

    private fun next(): Byte = buf[pos++]
}

// ---------------------------------------------------------------------------
// Candid cursor: leb128 / sleb128 / f64 reads + structural type-table skip.
// ---------------------------------------------------------------------------

private class CandidCursor(private val buf: ByteArray) {
    private var pos = 0

    fun byte(): Byte = buf[pos++]

    fun leb128(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    fun sleb128(): Long {
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = buf[pos++].toInt() and 0xff
            result = result or ((b.toLong() and 0x7f) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        if (shift < 64 && (b and 0x40) != 0) result = result or (-1L shl shift)
        return result
    }

    fun f64(): Double {
        val d = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double
        pos += 8
        return d
    }

    /** Advance past one type-table definition (opcode + its structure). */
    fun skipTypeDef() {
        when (sleb128()) {
            -18L, -19L -> sleb128()                 // opt / vec: one inner type ref
            -20L, -21L -> {                          // record / variant
                val fields = leb128()
                repeat(fields.toInt()) { leb128(); sleb128() } // id + type ref
            }
            // primitives (and anything else we don't expect): opcode only
        }
    }
}
