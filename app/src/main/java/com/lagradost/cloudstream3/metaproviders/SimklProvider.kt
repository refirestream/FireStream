package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Metadata only provider backed by [simkl.com](https://simkl.com).
 *
 * It supplies browsing, search and info pages, plus the signed in Simkl library as a
 * "Personal" home page row. It deliberately has no [loadLinks] implementation, every
 * [LoadResponse] is built with blank link data so the app reports "no links found"
 * instead of attempting playback.
 *
 * Based on CineSimklProvider from the CineStream extension (https://github.com/SaurabhKaperwan/CSX).
 */
class SimklProvider : MainAPI() {
    override var name = "Simkl"
    override var mainUrl = "https://simkl.com"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val providerType = ProviderType.MetaProvider
    override val supportedSyncNames = setOf(SyncIdName.Simkl)
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val apiUrl = "https://api.simkl.com"
    private val dataUrl = "https://data.simkl.in"
    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val headers = mapOf("Content-Type" to "application/json")
    private val repo = SyncRepo(AccountManager.simklApi)

    override val mainPage = mainPageOf(
        "/discover/trending/movies/today_500.json" to "Trending Movies Today",
        "/discover/trending/tv/today_500.json" to "Trending Shows Today",
        "/discover/trending/anime/today_500.json" to "Trending Anime Today",
        "/anime/airing?today?sort=rank" to "Airing Anime Today",
        "/tv/genres/all/all-types/kr/all-networks/this-year/popular-today?limit=$MEDIA_LIMIT" to "Trending Korean Shows",
        "/discover/dvd/releases_500.json" to "Trending Movie DVD Releases",
        "/discover/trending/movies/month_500.json" to "Trending Movies This Month",
        "/discover/trending/tv/month_500.json" to "Trending Series This Month",
        "/discover/trending/anime/month_500.json" to "Trending Anime This Month",
        "/movies/genres/all/all-types/all-countries/all-years/rank?limit=$MEDIA_LIMIT" to "Top Rated Movies",
        "/tv/genres/all/all-types/all-countries/all-networks/all-years/rank?limit=$MEDIA_LIMIT" to "Top Rated Shows",
        "/anime/genres/all/all-types/all-countries/all-networks/all-years/rank?limit=$MEDIA_LIMIT" to "Top Rated Anime",
        "/tv/genres/all/all-types/kr/all-networks/all-years/rank?limit=$MEDIA_LIMIT" to "Top Rated Korean Shows",
        "/anime/premieres/soon?type=all&limit=$MEDIA_LIMIT" to "Upcoming Anime",
        PERSONAL_PAGE to "Personal",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == PERSONAL_PAGE) return getPersonalPage(request)

        // The static feeds are unpaginated dumps served with a one hour max-age, the api
        // endpoints are paginated and must not be cached.
        val isStaticFeed = request.data.endsWith(".json")
        val response = if (isStaticFeed) {
            app.get(
                dataUrl + request.data,
                headers = headers,
                cacheTime = STATIC_FEED_CACHE_MINUTES,
                cacheUnit = TimeUnit.MINUTES
            )
        } else {
            app.get("$apiUrl${request.data}&client_id=$clientId&page=$page", headers = headers)
        }

        val list = response.parsedSafe<Array<SimklMedia>>()
            ?.mapNotNull { it.toSearchResponse() }
            ?: return null

        return newHomePageResponse(
            list = HomePageList(request.name, list),
            hasNext = request.data.contains("limit=")
        )
    }

    /**
     * The library of the currently logged in Simkl account, one home page row per list.
     * Contributes no rows at all when signed out, rather than a row with an empty body.
     */
    private suspend fun getPersonalPage(request: MainPageRequest): HomePageResponse {
        val context = activity
        val lists = if (repo.authUser() == null || context == null) {
            emptyList()
        } else {
            repo.library().getOrThrow()?.allLibraryLists?.mapNotNull { list ->
                if (list.items.isEmpty()) return@mapNotNull null
                HomePageList("${request.name}: ${list.name.asString(context)}", list.items)
            }.orEmpty()
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList = coroutineScope {
        val resultsPerType = listOf("movie", "tv", "anime").map { type ->
            async {
                app.get(
                    "$apiUrl/search/$type?q=$query&page=$page&limit=$MEDIA_LIMIT&extended=full&client_id=$clientId",
                    headers = headers
                ).parsedSafe<Array<SimklMedia>>()
                    ?.mapNotNull { it.toSearchResponse() }
                    .orEmpty()
            }
        }.awaitAll()

        // Interleave the three lists so every media type is represented near the top.
        val combined = buildList {
            for (i in 0 until (resultsPerType.maxOfOrNull { it.size } ?: 0)) {
                resultsPerType.forEach { results -> results.getOrNull(i)?.let(::add) }
            }
        }

        newSearchResponseList(combined)
    }

    override suspend fun load(url: String): LoadResponse {
        val (simklId, simklType) = parseSimklUrl(url)
        val media = getMedia(simklType, simklId)
            ?: throw ErrorLoadingException("Simkl has no entry for $url")

        val isAnime = media.type == "anime"
        val ids = media.ids
        val imdbId = ids?.imdb
        val anilistId = ids?.anilist?.toIntOrNull()
        val aniList = anilistId?.let { getAniListInfo(it) }
        val title = aniList?.englishTitle ?: media.displayTitle
            ?: throw ErrorLoadingException("Simkl entry $url has no title")

        // The metadata addon is IMDb keyed and only knows "movie" and "series", so anime,
        // which is tracked on AniList instead, is left out.
        val addonMeta = if (isAnime) {
            null
        } else {
            getAddonMeta(if (media.type == "movie") "movie" else "series", imdbId)
        }

        val trailer = media.trailers?.firstNotNullOfOrNull { it.youtube }
        val poster = addonMeta?.poster ?: posterUrl(media.poster)
        val background = addonMeta?.background
            ?: firstReachable(imdbBackgroundUrl(imdbId))
            ?: aniList?.banner
            ?: fanartUrl(media.fanart)
            ?: trailer?.let { "https://img.youtube.com/vi/$it/maxresdefault.jpg" }

        val recommendations = buildList {
            media.relations?.forEach { relation ->
                val prefix = relation.relationType
                    ?.replaceFirstChar { it.uppercase() }
                    ?.let { "($it) " }
                    .orEmpty()
                relation.toSearchResponse(prefix, media.type)?.let(::add)
            }
            media.userRecommendations?.forEach { it.toSearchResponse("", media.type)?.let(::add) }
        }

        val isMovie = media.type == "movie" || (isAnime && media.animeType == "movie")
        if (isMovie) {
            return newMovieLoadResponse(
                title,
                url,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                dataUrl = NO_LINKS
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = buildPlot(media, aniList, isAnime)
                this.tags = media.genres
                this.duration = media.runtimeInMinutes
                this.year = media.year
                this.score = Score.from10(media.rating)
                this.actors = addonMeta?.cast
                this.logoUrl = addonMeta?.logo ?: imdbLogoUrl(imdbId)
                this.recommendations = recommendations
                this.contentRating = media.certification
                this.comingSoon = isUpcoming(media.released)
                this.addSimklId(simklId.toIntOrNull())
                this.addAniListId(anilistId)
                this.addMalId(ids?.mal?.toIntOrNull())
                this.addTrailer(listOfNotNull(trailer?.let { "https://www.youtube.com/watch?v=$it" }))
            }
        }

        val episodes = app.get(
            "$apiUrl/tv/episodes/$simklId?client_id=$clientId&extended=full",
            headers = headers
        ).parsedSafe<Array<SimklEpisode>>()
            .orEmpty()
            .filter { it.type != "special" }
            .map { episode ->
                newEpisode(NO_LINKS) {
                    this.name = episode.title?.plus(
                        if (episode.aired == false) " • [UPCOMING]" else ""
                    )
                    this.season = episode.season
                    this.episode = episode.episode
                    this.description = episode.description
                    this.posterUrl = episodePosterUrl(episode.img)
                    addDate(episode.date, "yyyy-MM-dd'T'HH:mm:ss")
                }
            }

        return newAnimeLoadResponse(
            title,
            url,
            if (isAnime) TvType.Anime else TvType.TvSeries
        ) {
            addEpisodes(DubStatus.Subbed, episodes)
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = buildPlot(media, aniList, isAnime)
            this.tags = media.genres
            this.duration = media.runtimeInMinutes
            this.year = media.year
            this.score = Score.from10(media.rating)
            this.actors = addonMeta?.cast
            this.logoUrl = addonMeta?.logo ?: imdbLogoUrl(imdbId)
            this.showStatus = when (media.status) {
                "airing" -> ShowStatus.Ongoing
                "ended" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = recommendations
            this.contentRating = media.certification
            this.addSimklId(simklId.toIntOrNull())
            this.addAniListId(anilistId)
            this.addMalId(ids?.mal?.toIntOrNull())
            this.addTrailer(listOfNotNull(trailer?.let { "https://www.youtube.com/watch?v=$it" }))
        }
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        return if (name == SyncIdName.Simkl) "$mainUrl/tv/$id" else null
    }

    /**
     * Simkl rejects an id that is looked up under the wrong catalogue, and the library returns
     * every entry under `/tv/`, so the remaining catalogues are tried as a fallback.
     */
    private suspend fun getMedia(type: String, id: String): SimklMedia? =
        (listOf(type) + (MEDIA_PATHS - type)).firstNotNullOfOrNull { fetchMedia(it, id) }

    /**
     * Fetches a single entry. Simkl answers with a redirect when the id is an alias of another
     * entry, and drops the query from the `Location` header, so redirects are followed manually.
     */
    private suspend fun fetchMedia(type: String, id: String): SimklMedia? {
        val response = app.get(
            "$apiUrl/$type/$id?client_id=$clientId&extended=full",
            headers = headers,
            allowRedirects = false
        )

        if (response.code !in 300..399) {
            // Errors are served as a json body, which would silently parse into an empty entry.
            if (!response.isSuccessful) return null
            return response.parsedSafe<SimklMedia>()?.takeIf { it.title != null }
        }

        var location = response.headers["Location"] ?: return null
        if (!location.contains("extended=full")) location += "&extended=full"
        return app.get(absoluteApiUrl(location), headers = headers)
            .takeIf { it.isSuccessful }
            ?.parsedSafe<SimklMedia>()
            ?.takeIf { it.title != null }
    }

    private fun absoluteApiUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> apiUrl + url
        else -> "$apiUrl/$url"
    }

    /** `https://simkl.com/movies/1234/some-slug` -> `1234` to `movies`. */
    private fun parseSimklUrl(url: String): Pair<String, String> {
        val id = url.split('/').firstOrNull { it.toIntOrNull() != null }.orEmpty()
        val type = when {
            url.contains("/movies/") -> "movies"
            url.contains("/anime/") -> "anime"
            else -> "tv"
        }
        return id to type
    }

    private fun buildPlot(media: SimklMedia, aniList: AniListInfo?, isAnime: Boolean): String? {
        if (!isAnime) return media.overview

        val overview = aniList?.description?.takeIf { it.isNotBlank() } ?: media.overview
        val altTitles = listOfNotNull(aniList?.englishTitle, media.englishTitle.decoded(), media.title)
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = "[<b>Alt Titles</b>: ", postfix = "]")
            ?: return overview

        return if (overview.isNullOrBlank()) altTitles else "$altTitles<br><br>$overview"
    }

    private fun isUpcoming(released: String?): Boolean {
        val releaseTime = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(released ?: return false)?.time
        }.getOrNull() ?: return false
        return unixTimeMS < releaseTime
    }

    /** Simkl image ids are relative paths, they need to be expanded and proxied for resizing. */
    private fun posterUrl(id: String?): String? =
        id?.let { "$IMAGE_PROXY$SIMKL_IMAGE_HOST/posters/${it}_m.webp" }

    private fun fanartUrl(id: String?): String? =
        id?.let { "$IMAGE_PROXY$SIMKL_IMAGE_HOST/fanart/${it}_medium.webp" }

    private fun episodePosterUrl(id: String?): String? =
        id?.let { "$IMAGE_PROXY$SIMKL_IMAGE_HOST/episodes/${it}_w.webp" }

    private fun imdbLogoUrl(imdbId: String?): String? =
        imdbId?.let { "${IMAGE_PROXY}https://live.metahub.space/logo/medium/$it/img" }

    private fun imdbBackgroundUrl(imdbId: String?): String? =
        imdbId?.let { "${IMAGE_PROXY}https://images.metahub.space/background/large/$it/img" }

    /** Metahub only covers a subset of titles, so a miss has to be detected before using it. */
    private suspend fun firstReachable(url: String?): String? {
        if (url == null) return null
        return runCatching { url.takeIf { app.head(it).code == 200 } }.getOrNull()
    }

    private suspend fun getAniListInfo(anilistId: Int): AniListInfo? {
        val query = """
            query (${'$'}id: Int) {
                Media (id: ${'$'}id, type: ANIME) {
                    title { english }
                    bannerImage
                    description(asHtml: false)
                }
            }
        """.trimIndent()

        val media = app.post(
            "https://graphql.anilist.co",
            json = mapOf("query" to query, "variables" to mapOf("id" to anilistId))
        ).parsedSafe<AniListResponse>()?.data?.media ?: return null

        fun String?.orNullIfBlank() = this?.takeUnless { it.isBlank() || it == "null" }

        return AniListInfo(
            englishTitle = media.title?.english.orNullIfBlank(),
            banner = media.bannerImage.orNullIfBlank(),
            description = media.description.orNullIfBlank()
        )
    }

    /**
     * Simkl serves no cast and only small artwork, so both are pulled from a Stremio metadata
     * addon keyed by IMDb id. Purely additive, every consumer falls back to the Simkl data.
     */
    private suspend fun getAddonMeta(type: String, imdbId: String?): AddonMeta? {
        if (imdbId == null) return null

        val meta = ADDON_META_URLS.firstNotNullOfOrNull { host ->
            runCatching {
                app.get("$host/meta/$type/$imdbId.json", timeout = ADDON_TIMEOUT_SECONDS)
                    .parsedSafe<AddonMetaResponse>()
                    ?.meta
            }.getOrNull()
        } ?: return null

        fun String?.proxied() = this?.takeUnless { it.isBlank() || it == "null" }?.let { IMAGE_PROXY + it }

        return AddonMeta(
            cast = meta.extras?.cast?.mapNotNull { member ->
                val actorName = member.name?.takeUnless { it.isBlank() || it == "null" }
                    ?: return@mapNotNull null
                ActorData(
                    Actor(actorName, member.photo.proxied()),
                    roleString = member.character?.takeUnless { it.isBlank() || it == "null" }
                )
            }?.takeIf { it.isNotEmpty() },
            poster = meta.poster.proxied(),
            background = meta.background.proxied(),
            logo = meta.logo.proxied()
        )
    }

    private fun SimklMedia.toSearchResponse(): SearchResponse? {
        val title = englishSearchTitle.decoded() ?: displayTitle ?: return null
        // Some feeds use the singular "movie" while the site expects "movies".
        val path = url?.replace("/movie/", "/movies/") ?: return null
        return newMovieSearchResponse(title, mainUrl + path) {
            this.posterUrl = posterUrl(poster)
            this.score = Score.from10(rating)
        }
    }

    /**
     * @param parentType type of the entry this is related to, used when the entry carries no
     * type of its own, which is the case for the relations of an anime.
     */
    private fun SimklRelated.toSearchResponse(prefix: String, parentType: String?): SearchResponse? {
        val title = englishTitle.decoded() ?: title ?: return null
        val relatedIds = ids ?: return null
        val id = relatedIds.simkl ?: return null
        val slug = relatedIds.slug?.let { "/$it" }.orEmpty()
        return newMovieSearchResponse(prefix + title, "$mainUrl/${sitePath(type ?: parentType)}/$id$slug") {
            this.posterUrl = posterUrl(poster)
        }
    }

    /**
     * Simkl html escapes its english titles, `Frieren: Beyond Journey&#039;s End`, while leaving
     * every other field alone. Only entities are decoded, any markup is left as is.
     */
    private fun String?.decoded(): String? = this?.let { Parser.unescapeEntities(it, false) }

    /** English title where Simkl has one, falling back to the original, usually romaji, title. */
    private val SimklMedia.displayTitle: String?
        get() = englishTitle.decoded() ?: title

    /** Simkl serves movies under `/movies/`, everything that is not anime under `/tv/`. */
    private fun sitePath(type: String?): String = when (type) {
        "movie" -> "movies"
        "anime" -> "anime"
        else -> "tv"
    }

    private data class AniListInfo(
        val englishTitle: String?,
        val banner: String?,
        val description: String?,
    )

    private data class AddonMeta(
        val cast: List<ActorData>?,
        val poster: String?,
        val background: String?,
        val logo: String?,
    )

    data class SimklMedia(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("en_title") val englishTitle: String? = null,
        /** Only the `/search` endpoints use this key, the detail endpoints use `en_title`. */
        @JsonProperty("title_en") val englishSearchTitle: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("released") val released: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("anime_type") val animeType: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("fanart") val fanart: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("certification") val certification: String? = null,
        @JsonProperty("status") val status: String? = null,
        /** Plain minutes (`120`) on the api endpoints, `"2h 53m"` on the static feeds. */
        @JsonProperty("runtime") val runtime: Any? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("ids") val ids: SimklIds? = null,
        @JsonProperty("ratings") val ratings: SimklRatings? = null,
        @JsonProperty("trailers") val trailers: List<SimklTrailer>? = null,
        @JsonProperty("relations") val relations: List<SimklRelated>? = null,
        @JsonProperty("users_recommendations") val userRecommendations: List<SimklRelated>? = null,
    ) {
        val runtimeInMinutes: Int?
            get() {
                val raw = runtime?.toString() ?: return null
                val hours = HOURS_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
                val minutes = MINUTES_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
                if (hours == null && minutes == null) return raw.filter { it.isDigit() }.toIntOrNull()
                return (hours ?: 0) * 60 + (minutes ?: 0)
            }

        val rating: Double?
            get() = ratings?.mal?.rating ?: ratings?.imdb?.rating
    }

    data class SimklIds(
        @JsonProperty("simkl") val simkl: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("imdb") val imdb: String? = null,
        @JsonProperty("mal") val mal: String? = null,
        @JsonProperty("anilist") val anilist: String? = null,
    )

    data class SimklRatings(
        @JsonProperty("imdb") val imdb: SimklRating? = null,
        @JsonProperty("mal") val mal: SimklRating? = null,
    )

    data class SimklRating(
        @JsonProperty("rating") val rating: Double? = null,
    )

    data class SimklTrailer(
        @JsonProperty("youtube") val youtube: String? = null,
    )

    data class SimklRelated(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("en_title") val englishTitle: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        /** Absent on the relations of an anime, they are always anime themselves. */
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("relation_type") val relationType: String? = null,
        @JsonProperty("ids") val ids: SimklIds? = null,
    )

    data class SimklEpisode(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("aired") val aired: Boolean? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("date") val date: String? = null,
    )

    data class AddonMetaResponse(
        @JsonProperty("meta") val meta: AddonMetaEntry? = null,
    )

    data class AddonMetaEntry(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("app_extras") val extras: AddonMetaExtras? = null,
    )

    data class AddonMetaExtras(
        @JsonProperty("cast") val cast: List<AddonCastMember>? = null,
    )

    data class AddonCastMember(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("photo") val photo: String? = null,
        @JsonProperty("character") val character: String? = null,
    )

    data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null,
    )

    data class AniListData(
        @JsonProperty("Media") val media: AniListMedia? = null,
    )

    data class AniListMedia(
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    data class AniListTitle(
        @JsonProperty("english") val english: String? = null,
    )

    companion object {
        private const val MEDIA_LIMIT = 10
        private const val PERSONAL_PAGE = "personal"
        private const val STATIC_FEED_CACHE_MINUTES = 60

        /** Blank link data, [com.lagradost.cloudstream3.ui.APIRepository] treats it as "no links". */
        private const val NO_LINKS = ""

        private const val SIMKL_IMAGE_HOST = "https://simkl.in"
        private const val IMAGE_PROXY = "https://wsrv.nl/?url="

        /** The catalogues an entry can live under, matching both the site and the api paths. */
        private val MEDIA_PATHS = listOf("movies", "tv", "anime")

        private val HOURS_REGEX = Regex("""(\d+)\s*h""")
        private val MINUTES_REGEX = Regex("""(\d+)\s*m""")

        private const val ADDON_TIMEOUT_SECONDS = 6L
        private val ADDON_META_URLS = listOf(
            "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7",
            "https://94c8cb9f702d-tmdb-addon.baby-beamup.club"
        )
    }
}
