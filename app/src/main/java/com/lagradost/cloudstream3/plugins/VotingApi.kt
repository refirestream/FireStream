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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Voting via the fire-backend ICP canister (see fire-backend/CLAUDE.md).
 *
 * The canister exposes a plain JSON/HTTP face over the IC gateway, so this is
 * just two ordinary requests — no IC agent, no CBOR, no Candid:
 *
 *   GET  /score?subject=<64-hex>         -> {"score": <0..100 | null>}   null = "New"
 *   POST /vote  {"subject":..,"dir":..}  -> {"ok": true}                 dir = up|down
 *
 * Reads are queries (instant, uncertified); votes are update calls that reply
 * only after the ballot commits. Every request is anonymous.
 *
 * Anonymous ballots aren't deduped by the backend — each vote just adds to the
 * subject's Wilson-score accumulator, with no per-voter record. So a
 * same-direction re-cast is only blocked client-side, and a flip sends a new
 * opposing ballot instead of replacing the old one: one person going up then
 * down leaves the subject at one up, one down (~50%).
 */
object VotingApi {
    private const val LOGKEY = "VotingApi"

    // --- Deployment config -------------------------------------------------
    // fire-backend on the IC. The canister id is the raw-gateway subdomain, so
    // requests need no ?canisterId= param. Reads must use the `.raw.` domain:
    // get_score is an uncertified query and a certified domain rejects it (see
    // fire-backend/CLAUDE.md). No trailing slash.
    //   BASE_URL = https://<canister-id>.raw.icp0.io
    private const val BASE_URL = "https://4lisw-jiaaa-aaaab-agzhq-cai.raw.icp0.io"

    /**
     * Master switch for voting. UI hides the thumbs and flame badge while this
     * is false, rather than showing dead controls.
     */
    const val ENABLED = true

    private val configured
        get() = ENABLED && BASE_URL.isNotBlank()

    // Retries for re-reading the score right after a vote, since get_score can
    // briefly race a lagging query node and return the pre-vote state.
    private const val RETRY_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 400L

    /** The subject key: SHA-256 of the URL as 64 lowercase hex chars. */
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

    // --- JSON wire types ---------------------------------------------------

    /** `GET /score` reply. `score` null = the canister answered "New". */
    private data class ScoreResponse(@JsonProperty("score") val score: Double?)

    // --- Score cache -------------------------------------------------------
    // In-memory map in front of the DataStore (setKey/getKey) disk cache, so a
    // cold start can still paint scores before any network call.
    //
    // Reads are stale-while-revalidate: `peekScores` returns whatever is on
    // disk regardless of age, and `getScores` refreshes anything past the TTL.

    /** Persisted cache entry. `score` null = the canister answered "New". */
    @Serializable
    data class CachedScore(
        @JsonProperty("score") @SerialName("score") val score: Double?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: Long,
    )

    private const val SCORE_CACHE_FOLDER = "cs3-score-cache"
    private const val SCORE_TTL_MS = 6L * 60L * 60L * 1000L

    /**
     * Cap on simultaneous score queries. A repository can list 50+ plugins and
     * firing that many sockets at once starves the connection pool and stalls
     * icon loading in the same list.
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
                // Clear on completion, not on the caller leaving — a caller
                // cancelled mid-await can't run suspending cleanup, and that
                // would strand this entry here forever.
                started.invokeOnCompletion { inFlight.remove(pluginUrl, started) }
            }
        }
        // job belongs to scoreScope, so cancelling this caller won't cancel the
        // fetch other callers are sharing.
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

    // per-subject vote: true = up, false = down, absent = not voted.
    fun votedDirection(pluginUrl: String): Boolean? =
        getKey<Boolean>("cs3-votes/${transformUrl(pluginUrl)}")

    fun hasVoted(pluginUrl: String): Boolean = votedDirection(pluginUrl) != null

    private val voteLock = Mutex()

    // Voting works on any plugin URL, installed or not — that URL is the subject.
    suspend fun vote(pluginUrl: String, up: Boolean): Double? {
        voteLock.withLock {
            // Same direction already cast -> no-op. Anonymous ballots can't be
            // deduped server-side, so this local check is what makes a re-cast
            // idempotent.
            if (votedDirection(pluginUrl) == up) {
                main {
                    Toast.makeText(context, R.string.already_voted, Toast.LENGTH_SHORT).show()
                }
                return getScore(pluginUrl)
            }

            // New vote or a flip — both go as a plain ballot. A flip adds an
            // opposing ballot rather than replacing the old one; the stored key
            // just tracks which thumb the UI shows as selected.
            if (castVote(pluginUrl, up)) {
                setKey("cs3-votes/${transformUrl(pluginUrl)}", up)
                // The vote committed before /vote replied, but a query can still
                // hit a lagging node and read the pre-vote state. Retry with
                // backoff and only cache once settled.
                return readScoreSettled(pluginUrl)?.also { store(pluginUrl, it) }
            }

            return getScore(pluginUrl)
        }
    }

    // --- Canister calls ----------------------------------------------------

    /**
     * `GET /score?subject=<hex>` -> TrustScore % (0..100) or null ("New").
     *
     * Success means the canister answered; the value inside may still be null
     * ("New"). Failure means the call did not complete, which callers must not
     * confuse with "New" — only a success is worth caching.
     */
    private suspend fun readScore(pluginUrl: String): Result<Double?> {
        if (!configured) {
            Log.w(LOGKEY, "voting disabled or unconfigured; skipping score read")
            return Result.failure(IllegalStateException("voting disabled or unconfigured"))
        }
        val subject = transformUrl(pluginUrl)
        return try {
            val url = "$BASE_URL/score?subject=$subject"
            val res = app.get(url)
            if (!res.isSuccessful) {
                Log.e(LOGKEY, "score read failed: HTTP ${res.code}")
                return Result.failure(Exception("score read failed: HTTP ${res.code}"))
            }
            Result.success(parseJson<ScoreResponse>(res.text).score)
        } catch (t: Throwable) {
            Log.e(LOGKEY, "score read error: ${Log.getStackTraceString(t)}")
            Result.failure(t)
        }
    }

    /**
     * `readScore` retried right after a vote, when a score is guaranteed to
     * exist — so null means "not committed yet on this node", retry before
     * giving up.
     */
    private suspend fun readScoreSettled(pluginUrl: String): Double? {
        repeat(RETRY_ATTEMPTS) { i ->
            readScore(pluginUrl).getOrNull()?.let { return it }
            if (i < RETRY_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    /** `POST /vote {subject, dir}`. Returns true if the canister accepted it. */
    private suspend fun castVote(pluginUrl: String, up: Boolean): Boolean {
        if (!configured) {
            Log.w(LOGKEY, "voting disabled or unconfigured; cannot vote")
            return false
        }
        val subject = transformUrl(pluginUrl)
        return try {
            val url = "$BASE_URL/vote"
            val res = app.post(
                url,
                headers = mapOf("Content-Type" to "application/json"),
                json = mapOf("subject" to subject, "dir" to if (up) "up" else "down"),
            )
            if (!res.isSuccessful) Log.e(LOGKEY, "vote failed: HTTP ${res.code}")
            res.isSuccessful
        } catch (t: Throwable) {
            Log.e(LOGKEY, "vote error: ${Log.getStackTraceString(t)}")
            false
        }
    }
}
