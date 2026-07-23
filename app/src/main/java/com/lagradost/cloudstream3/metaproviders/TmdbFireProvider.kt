package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SourceApi
import com.lagradost.cloudstream3.SourceApiHolder
import com.lagradost.cloudstream3.SourceRequest
import com.lagradost.cloudstream3.SubtitleFile
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
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Based on CineTmdbProvider from the CineStream extension (https://github.com/SaurabhKaperwan/CSX).
 */
class TmdbFireProvider : MainAPI() {
    override var name = PROVIDER_NAME
    override var mainUrl = "https://www.themoviedb.org"

    /**
     * Follows the app language rather than being pinned to English.
     *
     * TMDB is not an English catalogue that happens to be translated, it serves the whole
     * catalogue in whichever language is asked for, so the provider's language is whatever the
     * user is reading the app in. This is what the provider language filter matches against
     * (see `getApiProviderLangSettings`), so a user who narrows that filter to their own
     * language keeps TMDB instead of losing it.
     *
     * Read on every access, because the app language can change after this instance is built:
     * that only recreates the activity, and the provider list survives it.
     *
     * A clone site assigns its own language, which then wins for that instance
     * (see the `USER_PROVIDER_API` handling in MainActivity).
     */
    override var lang: String
        get() = languageOverride ?: appLanguageCode()
        set(value) {
            languageOverride = value
        }

    private var languageOverride: String? = null

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    private val apiUrl = "https://api.themoviedb.org/3"
    private val apiKey = BuildConfig.TMDB_API_KEY

    // The regional `region` param is appended per request in getMainPage, not baked in here, so
    // the rows follow the user's language (see appRegion) rather than staying pinned to one region.
    override val mainPage = mainPageOf(
        "trending/movie/week" to "Trending Movies This Week",
        "trending/tv/week" to "Trending Shows This Week",
        "discover/tv?with_networks=213" to "Netflix",
        "discover/tv?with_networks=1024" to "Amazon",
        "discover/tv?with_networks=2739" to "Disney+",
        "discover/tv?with_networks=453" to "Hulu",
        "discover/tv?with_networks=2552" to "Apple TV+",
        "discover/tv?with_networks=49" to "HBO",
        "discover/tv?with_networks=4330" to "Paramount+",
        "discover/tv?with_networks=3353" to "Peacock",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated Shows",
        "discover/tv?with_keywords=$ANIME_KEYWORDS&sort_by=popularity.desc&air_date.gte=${today()}&air_date.lte=${today()}" to "Anime Airing Today",
        "discover/tv?with_keywords=$ANIME_KEYWORDS&sort_by=popularity.desc&air_date.gte=${today()}&air_date.lte=${nextWeek()}" to "Anime On The Air",
        "discover/movie?with_keywords=$ANIME_KEYWORDS&sort_by=popularity.desc" to "Anime Movies",
        "discover/tv?with_original_language=ko&sort_by=popularity.desc" to "Korean Shows",
        "discover/movie?with_origin_country=IN&sort_by=popularity.desc&release_date.gte=${lastWeek()}&release_date.lte=${today()}" to "Trending Indian Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Every row is either a movie or a tv feed, apart from the mixed trending one, which
        // carries a media_type per entry.
        val type = if (request.data.startsWith("movie") || request.data.contains("/movie")) {
            "movie"
        } else {
            "tv"
        }
        // Feeds that carry no query of their own (trending, top_rated) open the parameters with a
        // "?"; the discover feeds already have one, so they continue it with "&".
        val separator = if (request.data.contains('?')) "&" else "?"
        val response = app.get(
            "$apiUrl/${request.data}${separator}api_key=$apiKey&language=${appLanguageTag()}" +
                    "&region=${appRegion()}&without_keywords=$ADULT_KEYWORDS" +
                    homeReleaseFilter(request.data) + "&page=$page",
            cacheTime = CACHE_MINUTES,
            cacheUnit = TimeUnit.MINUTES
        ).parsedSafe<Results>() ?: return null

        val results = response.results ?: return null

        // Drop cinema-only movies. Discover feeds already filter this at the
        // request (homeReleaseFilter), so only trending/top_rated movies need
        // pruning here; TV has no theatrical window. Runs before the
        // ITEMS_PER_ROW cap so the row still fills up. See hasHomeRelease.
        val watchable = if (type == "movie" && !request.data.startsWith("discover/movie")) {
            coroutineScope {
                results
                    .map { media -> async { media.takeIf { it.hasHomeRelease() } } }
                    .awaitAll()
                    .filterNotNull()
            }
        } else {
            results
        }

        // Title-lettered backdrops need a per-item images request (the list's
        // backdrop_path is always textless), so cap the row instead of firing
        // ~20 requests a page.
        val list = coroutineScope {
            watchable
                .take(ITEMS_PER_ROW)
                .map { media -> async { media.toSearchResponse(type, media.titledBackdropUrl(type)) } }
                .awaitAll()
                .filterNotNull()
        }
        return newHomePageResponse(
            // The rows hang a wide title backdrop on each card (see titledBackdropUrl), so lay them
            // out as horizontal cards rather than the default portrait poster ones.
            list = HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = page < (response.totalPages ?: 1)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val response = app.get(
            "$apiUrl/search/multi?api_key=$apiKey&language=${appLanguageTag()}&query=$query&page=$page"
        ).parsedSafe<Results>()

        return newSearchResponseList(
            response?.results
                // A multi search also matches people, which have nothing to show a page for.
                ?.filter { it.mediaType != "person" }
                ?.mapNotNull { it.toSearchResponse() }
                .orEmpty(),
            hasNext = page < (response?.totalPages ?: 1)
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val (id, type) = parseTmdbUrl(url)
            ?: throw ErrorLoadingException("Not a TMDB media url: $url")

        val languageTag = appLanguageTag()
        val languageCode = languageTag.substringBefore('-')

        val media = app.get(
            "$apiUrl/$type/$id?api_key=$apiKey&language=$languageTag" +
                    "&append_to_response=$APPEND_TO_RESPONSE" +
                    // "null" keeps the language-less logos, which are usually the original
                    // wordmark and the only art many titles have.
                    "&include_image_language=$languageCode,en,null"
        ).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("TMDB has no entry for $url")

        val title = media.title ?: media.name
            ?: throw ErrorLoadingException("TMDB entry $url has no title")

        val genres = media.genres?.mapNotNull { it.name }.orEmpty()
        val keywords = (media.keywords?.results ?: media.keywords?.keywords)
            ?.mapNotNull { it.name }
            ?.map { keyword -> keyword.replaceFirstChar { it.uppercase() } }
        val releaseDate = media.releaseDate ?: media.firstAirDate
        val year = releaseDate?.substringBefore('-')?.toIntOrNull()

        val isAnimation = genres.contains("Animation")
        val isAnime = isAnimation && media.originalLanguage in ANIME_LANGUAGES

        val trailers = media.videos?.results
            .orEmpty()
            .sortedByDescending { it.type == "Trailer" }
            .mapNotNull { it.key?.let { key -> "https://www.youtube.com/watch?v=$key" } }

        val actors = media.credits?.cast?.mapNotNull { cast ->
            val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                Actor(actorName, imageUrl(cast.profilePath)),
                roleString = cast.character
            )
        }

        val recommendations = media.recommendations?.results?.mapNotNull { it.toSearchResponse(type) }

        val isMovie = type == "movie"
        val tvType = when {
            isMovie && isAnime -> TvType.AnimeMovie
            isMovie -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }

        // TMDB itself has no links, so what gets stored is everything the sources need to go
        // looking for them, see loadLinks.
        val sourceRequest = SourceRequest(
            title = title,
            tvType = tvType,
            originalTitle = media.originalTitle ?: media.originalName,
            year = year,
            imdbId = media.externalIds?.imdbId,
            tmdbId = id.toIntOrNull(),
        )

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                url,
                tvType,
                dataUrl = sourceRequest.toJson()
            ) {
                this.posterUrl = imageUrl(media.posterPath, ORIGINAL_IMAGE_SIZE)
                this.backgroundPosterUrl = imageUrl(media.backdropPath, ORIGINAL_IMAGE_SIZE)
                this.plot = media.overview?.takeIf { it.isNotBlank() }
                this.tags = keywords?.takeIf { it.isNotEmpty() } ?: genres
                this.duration = media.runtime
                this.year = year
                this.score = Score.from10(media.voteAverage)
                this.actors = actors
                this.logoUrl = media.logoUrl(languageCode)
                this.recommendations = recommendations
                this.contentRating = media.usAgeRating
                this.comingSoon = isUpcoming(releaseDate)
                this.addTMDbId(id)
                this.addImdbId(media.externalIds?.imdbId)
                this.addTrailer(trailers)
            }
        }

        val episodes = coroutineScope {
            media.seasons
                // Season 0 holds the specials, which do not belong in the episode list.
                ?.filter { it.seasonNumber != null && it.seasonNumber != 0 }
                ?.map { season ->
                    async {
                        app.get(
                            "$apiUrl/tv/$id/season/${season.seasonNumber}" +
                                    "?api_key=$apiKey&language=$languageTag"
                        ).parsedSafe<SeasonDetail>()?.episodes.orEmpty()
                    }
                }
                ?.awaitAll()
                ?.flatten()
                .orEmpty()
                .map { episode ->
                    val episodeRequest = sourceRequest.copy(
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                        airedYear = episode.airDate?.substringBefore('-')?.toIntOrNull(),
                    )
                    newEpisode(episodeRequest.toJson()) {
                        this.name = episode.name
                        this.season = episode.seasonNumber
                        this.episode = episode.episodeNumber
                        this.description = episode.overview
                        this.posterUrl = imageUrl(episode.stillPath)
                        this.score = Score.from10(episode.voteAverage)
                        this.runTime = episode.runtime
                        addDate(episode.airDate)
                    }
                }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            addEpisodes(DubStatus.Subbed, episodes)
            this.posterUrl = imageUrl(media.posterPath, ORIGINAL_IMAGE_SIZE)
            this.backgroundPosterUrl = imageUrl(media.backdropPath, ORIGINAL_IMAGE_SIZE)
            this.plot = media.overview?.takeIf { it.isNotBlank() }
            this.tags = keywords?.takeIf { it.isNotEmpty() } ?: genres
            this.duration = media.episodeRunTime?.firstOrNull()
            this.year = year
            this.score = Score.from10(media.voteAverage)
            this.actors = actors
            this.logoUrl = media.logoUrl(languageCode)
            this.showStatus = when (media.status) {
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = recommendations
            this.contentRating = media.usAgeRating
            this.addTMDbId(id)
            this.addImdbId(media.externalIds?.imdbId)
            this.addTrailer(trailers)
        }
    }

    /**
     * Hands the media over to every registered [SourceApi], as TMDB is a catalogue and holds no
     * links of its own. [data] is the [SourceRequest] that [load] stored on the movie or episode.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = tryParseJson<SourceRequest>(data) ?: return false
        return SourceApiHolder.loadLinksFromSources(request, subtitleCallback, callback)
    }

    /** `https://www.themoviedb.org/tv/1234-some-slug` -> `1234` to `tv`. */
    private fun parseTmdbUrl(url: String): Pair<String, String>? {
        val type = when {
            url.contains("/movie/") -> "movie"
            url.contains("/tv/") -> "tv"
            else -> return null
        }
        val id = url.substringAfter("/$type/").substringBefore('/').takeWhile { it.isDigit() }
        return if (id.isEmpty()) null else id to type
    }

    /**
     * @param posterOverride image to show instead of the portrait [posterPath], used by the home
     * rows to hang a wide title backdrop on their horizontal cards (see titledBackdropUrl).
     */
    private fun Media.toSearchResponse(
        fallbackType: String? = null,
        posterOverride: String? = null,
    ): SearchResponse? {
        val title = title ?: name ?: originalTitle ?: originalName ?: return null
        val id = id ?: return null
        val type = when (mediaType ?: fallbackType) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        return newMovieSearchResponse(title, "$mainUrl/$type/$id") {
            this.posterUrl = posterOverride ?: imageUrl(posterPath)
            this.score = Score.from10(voteAverage)
        }
    }

    /**
     * A backdrop with the title lettered onto it — reads better on the wide home cards than the
     * plain [Media.backdropPath] the list endpoints return, which is always the textless primary.
     * Lettered art only comes from a per-item images request.
     *
     * Prefers the app language, then English, then any other lettered backdrop (usually the
     * original), and falls back to the plain textless one if nothing lettered exists. No
     * `include_image_language` filter is sent, so backdrops in the title's original language
     * (e.g. a Korean show tagged "ko") aren't excluded.
     */
    private suspend fun Media.titledBackdropUrl(fallbackType: String? = null): String? {
        val plain = imageUrl(backdropPath, BACKDROP_IMAGE_SIZE)
        val id = id ?: return plain
        val type = when (mediaType ?: fallbackType) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return plain
        }
        val languageCode = appLanguageCode()
        val backdrops = app.get(
            "$apiUrl/$type/$id/images?api_key=$apiKey",
            // A title's backdrop art barely changes, so cache it far longer than the catalogue
            // rows themselves to keep these per-item requests off the slow path on later visits.
            cacheTime = BACKDROP_CACHE_HOURS,
            cacheUnit = TimeUnit.HOURS
        ).parsedSafe<Images>()?.backdrops
            ?.filter { !it.filePath.isNullOrBlank() && !it.isSvg }
            .orEmpty()

        val titled = backdrops.firstOrNull { it.language == languageCode }
            ?: backdrops.firstOrNull { it.language == "en" }
            ?: backdrops.firstOrNull { !it.language.isNullOrBlank() }
        return imageUrl(titled?.filePath, BACKDROP_IMAGE_SIZE) ?: plain
    }

    /**
     * Home-release filter for the discover feeds (trending/top_rated don't support it). TMDB's
     * `with_release_type` alone doesn't filter — it's the `release_date` range that does, so a
     * `release_date.lte` bound is added if the feed doesn't have one. Narrows a discover movie row
     * to titles already out digitally/physically in the request's region, avoiding the per-item
     * lookups [hasHomeRelease] needs for feeds without this filter.
     *
     * Single-region: unlike [hasHomeRelease], a release outside the request's region doesn't count.
     */
    private fun homeReleaseFilter(data: String): String {
        if (!data.startsWith("discover/movie")) return ""
        val releasedBound = if (data.contains("release_date.lte")) "" else "&release_date.lte=${today()}"
        return "&with_release_type=$DIGITAL_RELEASE|$PHYSICAL_RELEASE$releasedBound"
    }

    /**
     * Whether the movie already has a digital or physical release out, in any region. A
     * theatrical-only or still-upcoming title is cinema-only and has nothing to play, so it's
     * dropped from the home rows. Cached as long as the backdrops — release history barely moves.
     */
    private suspend fun Media.hasHomeRelease(): Boolean {
        val id = id ?: return false
        val countries = app.get(
            "$apiUrl/movie/$id/release_dates?api_key=$apiKey",
            cacheTime = BACKDROP_CACHE_HOURS,
            cacheUnit = TimeUnit.HOURS
        ).parsedSafe<ReleaseDates>()?.results ?: return false
        return countries.any { country ->
            country.releaseDates.orEmpty().any { entry ->
                (entry.type == DIGITAL_RELEASE || entry.type == PHYSICAL_RELEASE) &&
                        isReleased(entry.releaseDate)
            }
        }
    }

    private fun imageUrl(path: String?, size: String = DEFAULT_IMAGE_SIZE): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("/")) "$IMAGE_HOST/$size$path" else path
    }

    /** Inverse of [isUpcoming]: the date is set and has already passed. */
    private fun isReleased(releaseDate: String?): Boolean {
        if (releaseDate.isNullOrBlank()) return false
        // TMDB dates its release entries as "2021-05-25T00:00:00.000Z"; the day is enough.
        val releaseTime = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(releaseDate.substringBefore('T'))?.time
        }.getOrNull() ?: return false
        return unixTimeMS >= releaseTime
    }

    private fun isUpcoming(releaseDate: String?): Boolean {
        val releaseTime = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(releaseDate ?: return false)?.time
        }.getOrNull() ?: return false
        return unixTimeMS < releaseTime
    }

    data class Results(
        @JsonProperty("results") val results: List<Media>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: List<NamedEntry>? = null,
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null,
        @JsonProperty("videos") val videos: VideoResults? = null,
        @JsonProperty("images") val images: Images? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: Results? = null,
        @JsonProperty("content_ratings") val contentRatings: ContentRatings? = null,
        @JsonProperty("release_dates") val releaseDates: ReleaseDates? = null,
    ) {
        /** Certification of the US release, which is the only one TMDB fills in reliably. */
        val usAgeRating: String?
            get() {
                contentRatings?.results
                    ?.firstOrNull { it.country == "US" }
                    ?.rating
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                return releaseDates?.results
                    ?.firstOrNull { it.country == "US" }
                    ?.releaseDates
                    ?.firstNotNullOfOrNull { it.certification?.takeIf(String::isNotBlank) }
            }

        /**
         * Best title logo for [preferredLanguage], falling back to English and then to whatever
         * exists. Raster images are preferred throughout, as the app cannot render the svg ones
         * TMDB also serves.
         */
        fun logoUrl(preferredLanguage: String): String? {
            val logos = images?.logos?.filter { !it.filePath.isNullOrBlank() } ?: return null
            val logo = logos.firstOrNull { it.language == preferredLanguage && !it.isSvg }
                ?: logos.firstOrNull { it.language == "en" && !it.isSvg }
                ?: logos.firstOrNull { !it.isSvg }
                ?: logos.firstOrNull()
            return logo?.filePath?.let { "$IMAGE_HOST/$DEFAULT_IMAGE_SIZE$it" }
        }
    }

    data class NamedEntry(
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        /** Series list their keywords under `results`, movies under `keywords`. */
        @JsonProperty("results") val results: List<NamedEntry>? = null,
        @JsonProperty("keywords") val keywords: List<NamedEntry>? = null,
    )

    data class Season(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class SeasonDetail(
        @JsonProperty("episodes") val episodes: List<Episode>? = null,
    )

    data class Episode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
    )

    data class VideoResults(
        @JsonProperty("results") val results: List<Video>? = null,
    )

    data class Video(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class Images(
        @JsonProperty("logos") val logos: List<Image>? = null,
        @JsonProperty("backdrops") val backdrops: List<Image>? = null,
    )

    data class Image(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("iso_639_1") val language: String? = null,
    ) {
        val isSvg: Boolean get() = filePath?.endsWith(".svg", ignoreCase = true) == true
    }

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: List<Cast>? = null,
    )

    data class Cast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class ContentRatings(
        @JsonProperty("results") val results: List<ContentRating>? = null,
    )

    data class ContentRating(
        @JsonProperty("iso_3166_1") val country: String? = null,
        @JsonProperty("rating") val rating: String? = null,
    )

    data class ReleaseDates(
        @JsonProperty("results") val results: List<ReleaseDatesResult>? = null,
    )

    data class ReleaseDatesResult(
        @JsonProperty("iso_3166_1") val country: String? = null,
        @JsonProperty("release_dates") val releaseDates: List<ReleaseDateEntry>? = null,
    )

    data class ReleaseDateEntry(
        @JsonProperty("certification") val certification: String? = null,
        /** TMDB release type: 1 premiere, 2/3 theatrical, 4 digital, 5 physical, 6 TV. */
        @JsonProperty("type") val type: Int? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
    )

    companion object {
        /**
         * The provider's display name, also the app's default homepage provider (see
         * [DataStoreHelper.currentHomePage]). Referenced instead of the literal so a rename stays
         * in one place.
         */
        const val PROVIDER_NAME = "TheMovieDB"

        private const val CACHE_MINUTES = 60
        private const val BACKDROP_CACHE_HOURS = 48

        /** Cards shown per home row; each needs its own backdrop request, so keep it small. */
        private const val ITEMS_PER_ROW = 5
        private const val IMAGE_HOST = "https://image.tmdb.org/t/p"
        private const val DEFAULT_IMAGE_SIZE = "w500"
        private const val ORIGINAL_IMAGE_SIZE = "original"

        /**
         * TMDB offers backdrops only at fixed widths (w300, w780, w1280, original), all at the
         * native ~16:9 aspect. w780 keeps the lettered title art sharp on the home cards without
         * the weight of the wider sizes.
         */
        private const val BACKDROP_IMAGE_SIZE = "w780"

        private const val APPEND_TO_RESPONSE =
            "credits,external_ids,videos,images,keywords,recommendations,content_ratings,release_dates"

        /** TMDB release types that mean a title is watchable at home rather than only in cinemas. */
        private const val DIGITAL_RELEASE = 4
        private const val PHYSICAL_RELEASE = 5

        /** TMDB keyword ids for "anime" and "based on anime". */
        private const val ANIME_KEYWORDS = "210024|222243"

        /** TMDB keyword ids for pornographic and softcore content. */
        private const val ADULT_KEYWORDS = "190370|13059|226161|195669"

        private val ANIME_LANGUAGES = setOf("ja", "zh", "ko")

        private fun date(daysFromNow: Int = 0): String {
            val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysFromNow) }
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
        }

        private fun today() = date()
        private fun nextWeek() = date(7)
        private fun lastWeek() = date(-7)

        /**
         * App locale as an IETF tag ("pt-BR", not just "pt") — the form TMDB's `language` param
         * expects. Falls back to English when there's no Android context to read it from (e.g.
         * running against CloudstreamApi, where [CloudStreamApp] isn't loaded).
         */
        private fun appLanguageTag(): String =
            runCatching { CloudStreamApp.context?.let { getCurrentLocale(it) } }.getOrNull()
                ?: DEFAULT_LANGUAGE_TAG

        /**
         * The tag reduced to its language subtag, e.g. "pt-BR" -> "pt". The provider language
         * filter and its flag lookup both work in bare ISO 639-1 codes.
         */
        private fun appLanguageCode(): String = appLanguageTag().substringBefore('-')

        /**
         * TMDB `region` for the app language, so trending/top-rated reflect the user's region
         * instead of always the US. The locale is usually a bare language ("de", "ko"), so the
         * region is derived from [LANGUAGE_REGION]; a country subtag ("pt-BR") overrides that.
         * Falls back to [DEFAULT_REGION] when TMDB doesn't serve the resulting region.
         */
        private fun appRegion(): String {
            val tag = appLanguageTag()
            val country = tag.substringAfter('-', "").uppercase()
            if (country in TMDB_REGIONS) return country
            return LANGUAGE_REGION[tag.substringBefore('-')] ?: DEFAULT_REGION
        }

        private const val DEFAULT_LANGUAGE_TAG = "en-US"
        private const val DEFAULT_REGION = "US"

        /** ISO 3166-1 codes TMDB serves a `region` for; anything else it ignores. */
        private val TMDB_REGIONS = setOf(
            "AD", "AE", "AG", "AL", "AR", "AT", "AU", "BA", "BB", "BE", "BG", "BH", "BM", "BO",
            "BR", "BS", "CA", "CH", "CI", "CL", "CO", "CR", "CU", "CV", "CZ", "DE", "DK", "DO",
            "DZ", "EC", "EE", "EG", "ES", "FI", "FJ", "FR", "GB", "GF", "GH", "GI", "GP", "GQ",
            "GR", "GT", "HK", "HN", "HR", "HU", "ID", "IE", "IL", "IN", "IQ", "IS", "IT", "JM",
            "JO", "JP", "KE", "KR", "KW", "LB", "LC", "LI", "LT", "LV", "LY", "MA", "MC", "MD",
            "MK", "MT", "MU", "MX", "MY", "MZ", "NE", "NG", "NL", "NO", "NZ", "OM", "PA", "PE",
            "PF", "PH", "PK", "PL", "PS", "PT", "PY", "QA", "RO", "RS", "RU", "SA", "SC", "SE",
            "SG", "SI", "SK", "SM", "SN", "SV", "TC", "TH", "TN", "TR", "TT", "TW", "TZ", "UG",
            "US", "UY", "VA", "VE", "XK", "YE", "ZA", "ZM",
        )

        /**
         * Default TMDB region for each app language (ISO 639-1 -> ISO 3166-1). Only languages whose
         * primary country TMDB actually serves a region for are listed; the rest fall back to
         * [DEFAULT_REGION]. A locale's own country subtag ("pt-BR") overrides this in [appRegion].
         */
        private val LANGUAGE_REGION = mapOf(
            "af" to "ZA", "apc" to "LB", "ar" to "SA", "ars" to "SA", "as" to "IN", "bg" to "BG",
            "bn" to "IN", "ckb" to "IQ", "cs" to "CZ", "de" to "DE", "el" to "GR", "es" to "ES",
            "fil" to "PH", "fr" to "FR", "gl" to "ES", "hi" to "IN", "hr" to "HR", "hu" to "HU",
            "in" to "ID", "it" to "IT", "iw" to "IL", "ja" to "JP", "kn" to "IN", "ko" to "KR",
            "lt" to "LT", "lv" to "LV", "mk" to "MK", "ml" to "IN", "ms" to "MY", "mt" to "MT",
            "ne" to "IN", "nl" to "NL", "nn" to "NO", "no" to "NO", "or" to "IN", "pl" to "PL",
            "pt" to "PT", "ro" to "RO", "ru" to "RU", "sk" to "SK", "sv" to "SE", "ta" to "IN",
            "tl" to "PH", "tr" to "TR", "ur" to "PK", "zh" to "TW",
        )
    }
}
