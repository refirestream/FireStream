package com.lagradost.cloudstream3.plugins

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
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
 * Only up-votes are cast from this client (upvote-only UI); the canister still
 * tracks up/down and returns the combined Wilson TrustScore.
 */
object VotingApi {
    private const val LOGKEY = "VotingApi"

    // --- Deployment config -------------------------------------------------
    // fire-backend runs on a local/LAN dfx replica (see scripts/deploy-lan.sh).
    // Fill these after `dfx deploy fire_backend`:
    //   REPLICA_URL  = http://<lan-host-ip>:4943   (no trailing slash)
    //   CANISTER_ID  = output of `dfx canister id fire_backend`
    private const val REPLICA_URL = "http://127.0.0.1:4943"
    private const val CANISTER_ID = "" // e.g. "uxrrr-q7777-77774-qaaaq-cai"

    private val CBOR = "application/cbor".toMediaType()
    private val configured get() = CANISTER_ID.isNotBlank()

    private fun transformUrl(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${url}#funny-salt".toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }

    suspend fun SitePlugin.getScore(): Double? = getScore(url)
    fun SitePlugin.hasVoted(): Boolean = hasVoted(url)
    suspend fun SitePlugin.vote(): Double? = vote(url)
    fun SitePlugin.canVote(): Boolean = canVote(this.url)

    // score cache: absent = unknown, present (possibly null) = fetched ("New" = null)
    private val scoreCache = mutableMapOf<String, Double?>()

    suspend fun getScore(pluginUrl: String): Double? {
        if (scoreCache.containsKey(pluginUrl)) return scoreCache[pluginUrl]
        return readScore(pluginUrl).also { scoreCache[pluginUrl] = it }
    }

    fun hasVoted(pluginUrl: String): Boolean =
        getKey<Boolean>("cs3-votes/${transformUrl(pluginUrl)}") ?: false

    fun canVote(pluginUrl: String): Boolean =
        PluginManager.urlPlugins.contains(pluginUrl)

    private val voteLock = Mutex()

    suspend fun vote(pluginUrl: String): Double? {
        voteLock.withLock {
            if (!canVote(pluginUrl)) {
                main {
                    Toast.makeText(context, R.string.extension_install_first, Toast.LENGTH_SHORT)
                        .show()
                }
                return getScore(pluginUrl)
            }

            if (hasVoted(pluginUrl)) {
                main {
                    Toast.makeText(context, R.string.already_voted, Toast.LENGTH_SHORT).show()
                }
                return getScore(pluginUrl)
            }

            if (castVote(pluginUrl, up = true)) {
                setKey("cs3-votes/${transformUrl(pluginUrl)}", true)
                scoreCache.remove(pluginUrl) // force a fresh read of the updated score
            }

            return getScore(pluginUrl)
        }
    }

    // --- Canister calls ----------------------------------------------------

    /** Query `get_score(subject)` -> TrustScore % (0..100) or null ("New"). */
    private suspend fun readScore(pluginUrl: String): Double? {
        if (!configured) {
            Log.w(LOGKEY, "CANISTER_ID not set; skipping get_score")
            return null
        }
        val subject = transformUrl(pluginUrl)
        val envelope = queryEnvelope("get_score", candidTextArg(subject))
        val reply = post("query", envelope) ?: return null
        val arg = replyArg(reply) ?: return null
        return decodeOptFloat64(arg)
    }

    /** Update `vote(subject, dir)`. Returns true if the replica accepted it. */
    private suspend fun castVote(pluginUrl: String, up: Boolean): Boolean {
        if (!configured) {
            Log.w(LOGKEY, "CANISTER_ID not set; cannot vote")
            return false
        }
        val subject = transformUrl(pluginUrl)
        val envelope = callEnvelope("vote", candidVoteArg(subject, up))
        // Anonymous update call: 2xx/202 = accepted. Result is confirmed by the
        // subsequent get_score re-query, so no read_state polling is needed.
        return post("call", envelope) != null
    }

    private suspend fun post(endpoint: String, body: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = "$REPLICA_URL/api/v2/canister/$CANISTER_ID/$endpoint"
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
            4 -> List(length(info).toInt()) { read() }                   // array
            5 -> LinkedHashMap<String, Any?>().apply {                   // map
                repeat(length(info).toInt()) { put(read().toString(), read()) }
            }
            6 -> read()                             // tag: skip, read payload
            7 -> if (info == 22 || info == 23) null else length(info) // null/undef/simple
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
