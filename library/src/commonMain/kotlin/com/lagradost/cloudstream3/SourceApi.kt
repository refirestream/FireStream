@file:OptIn(ExperimentalUuidApi::class)

package com.lagradost.cloudstream3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.utils.AtomicMutableList
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import kotlin.uuid.ExperimentalUuidApi

/** What kind of links a [SourceApi] hands back, so the app knows how to treat them. */
enum class SourceType {
    /** Plain http(s) links that the built in player can open directly. */
    Direct,

    /** Magnet links or .torrent urls, which need a torrent capable player. */
    Torrent,

    /** Links behind a debrid service, resolved through the user's account. */
    Debrid,

    /** The source only contributes subtitles, never streams. */
    Subtitle,
}

/**
 * Everything a [SourceApi] gets to identify what it should look for.
 *
 * A source is not tied to the provider the user opened, so it cannot rely on that provider's urls,
 * only on the ids and titles below. Fill in whatever is known, the source picks what it needs, e.g.
 * a scraper that searches by name uses [title] and [year], while one that queries an api by id uses
 * [imdbId] or [tmdbId].
 */
data class SourceRequest(
    /** Title as shown to the user, in the app language when the metadata provider has one. */
    val title: String,

    /** Type of the media, matched against [SourceApi.supportedTypes]. */
    val tvType: TvType,

    /** Title in the original language, which many scrapers index by for anime and asian media. */
    val originalTitle: String? = null,

    /** Release year of the movie, or of the first season for a series. */
    val year: Int? = null,

    /** Year the requested season aired, which differs from [year] on long running series. */
    val airedYear: Int? = null,

    /** Season number, null for movies. */
    val season: Int? = null,

    /** Episode number, null for movies. */
    val episode: Int? = null,

    /** IMDb id including the "tt" prefix, e.g. "tt0903747". */
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
)

abstract class SourceApi {
    /**
     * Stable identifier, unique across all sources, e.g. "my-source".
     *
     * Settings are persisted against it, so changing it later loses whatever the user had set for
     * this source. [name] is the one meant to be renamed freely.
     */
    abstract val id: String

    /** Name of the source as shown in the UI. */
    open val name: String = "NONE"

    /** Home url of the source, when it has one, e.g. for showing where its links come from. */
    open val url: String? = null

    /**
     * The language this source serves, as an IETF BCP 47 conformant tag, and "en" when it is not
     * bound to one. Check [com.lagradost.cloudstream3.utils.SubtitleHelper].
     */
    open val lang: String = "en"

    /** What the links coming out of [loadLinks] are, see [SourceType]. */
    open val sourceType: SourceType = SourceType.Direct

    /**
     * The types this source has anything to offer for. The app skips the source entirely for
     * everything else, so there is no need to check the type again inside [loadLinks].
     */
    open val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.OVA,
    )

    /** Set to true if the source needs a WebView, so it can be skipped where none is available. */
    open val usesWebView: Boolean = false

    /** Set to true if the source sits behind Cloudflare and needs a bypass to be reachable. */
    open val requiresCloudflareBypass: Boolean = false

    /** Determines which plugin a given source is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    abstract suspend fun loadLinks(
        request: SourceRequest,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean

    /** Whether this source is worth asking about [request] at all. */
    fun supports(request: SourceRequest): Boolean = supportedTypes.contains(request.tvType)

    /**
     * What the user sees this source called, falling back to [id] for a source that never set a
     * [name].
     */
    val label: String get() = name.takeIf { it.isNotBlank() && it != "NONE" } ?: id
}

const val SOURCE_TAG = "SourceApiInstance"

/** Keeps every registered [SourceApi], mirroring what [APIHolder] does for [MainAPI]. */
object SourceApiHolder {
    val allSources: AtomicMutableList<SourceApi> = atomicListOf()

    /**
     * Adds [source] to [allSources], refusing ids that are already taken, as settings are keyed by
     * id and two sources sharing one would overwrite each other.
     *
     * @return true if the source was added.
     */
    fun registerSource(source: SourceApi): Boolean = allSources.withLock {
        val existing = allSources.firstOrNull { it.id == source.id }
        if (existing != null) {
            Log.e(
                SOURCE_TAG,
                "Not adding ${source.name}, its id \"${source.id}\" is already used by ${existing.name}"
            )
            return@withLock false
        }
        allSources.add(source)
        true
    }

    fun getSourceFromId(id: String?): SourceApi? {
        if (id == null) return null
        return allSources.withLock { allSources.firstOrNull { it.id == id } }
    }

    /** Every source that has something to say about [request]. */
    fun sourcesFor(request: SourceRequest): List<SourceApi> = allSources.withLock {
        allSources.filter { it.supports(request) }.toList()
    }

    /**
     * Runs every source that supports [request] at the same time, and keeps going when one of them
     * throws, so a single broken source cannot take the others down with it.
     *
     * @return true if at least one source found something.
     */
    suspend fun loadLinksFromSources(
        request: SourceRequest,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return sourcesFor(request).amap { source ->
            // Sources name their links after what they found, not after themselves, so the source
            // is put in front of the name here rather than trusting every source to do it.
            val labelledCallback = { link: ExtractorLink -> callback(link.labelled(source.label)) }
            safeAsync { source.loadLinks(request, subtitleCallback, labelledCallback) } == true
        }.any { it }
    }

    /**
     * A copy of this link named "[label] whatever the source called it".
     *
     * Rebuilt rather than edited, as a link is immutable once a source hands it over. The subclass
     * is kept for the link types the app treats differently, anything else degrades to a plain
     * [ExtractorLink], which is all the player needs.
     */
    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    private fun ExtractorLink.labelled(label: String): ExtractorLink {
        val labelledName = "[$label] $name"
        return when (this) {
            is ExtractorLinkPlayList -> copy(name = labelledName)

            is DrmExtractorLink -> DrmExtractorLink(
                source = source,
                name = labelledName,
                url = url,
                referer = referer,
                quality = quality,
                type = type,
                headers = headers,
                extractorData = extractorData,
                kid = kid,
                key = key,
                uuid = uuid,
                kty = kty,
                keyRequestParameters = keyRequestParameters,
                licenseUrl = licenseUrl,
            ).also { it.audioTracks = audioTracks }

            else -> ExtractorLink(
                source = source,
                name = labelledName,
                url = url,
                referer = referer,
                quality = quality,
                headers = headers,
                extractorData = extractorData,
                type = type,
                audioTracks = audioTracks,
            )
        }
    }
}
