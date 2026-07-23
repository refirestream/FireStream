package com.lagradost.cloudstream3.sources

import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.SourceApi
import com.lagradost.cloudstream3.SourceRequest
import com.lagradost.cloudstream3.SourceType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * publicdomainmovie.net, a catalogue of films whose copyright has lapsed, every one of them hosted
 * on archive.org.
 *
 * The site has no search of its own, so the movie urls come from its sitemap and are matched
 * against the requested title. Slugs are mostly the plain title, but plenty carry a year or a
 * resolution ("the-general-1926", "topper-returns-720p-1941") and a duplicated title gets a
 * counter, so the match allows those extras and the movie page then says which film it really is.
 */
class PublicDomainMovieSource : SourceApi() {
    override val id = "public-domain-movie"
    override val name = "Public Domain Movie"
    override val sourceType = SourceType.Direct

    /** The site holds one file per title, no series. */
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon)

    override suspend fun loadLinks(
        request: SourceRequest,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slugs = candidates(index(), request)
        if (slugs.isEmpty()) return false

        // One unreachable page must not take the other candidates down with it.
        val movies = slugs.amap { slug -> safeAsync { movie(slug) } }
            .filterNotNull()
            .distinctBy { it.url }

        val playable = ofYear(movies, request.year)
        playable.forEach { movie ->
            callback(
                newExtractorLink(name, movie.name, movie.url) {
                    this.referer = "$MAIN_URL/"
                    this.quality = movie.quality
                }
            )
        }
        return playable.isNotEmpty()
    }

    /** What [slug] has to play, or null when its page holds no file. */
    private suspend fun movie(slug: String): Movie? {
        val document = app.get("$MAIN_URL/movie/$slug").document
        val year = document
            .selectFirst(".field-name-field-date .date-display-single")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val download = document.selectFirst(".field-name-download a[href]")?.attr("href")
            ?: return null
        val title = document.selectFirst(".field-name-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: slug.replace('-', ' ')

        val url = resolveFile(if (download.startsWith("http")) download else "$MAIN_URL$download")
        return Movie(
            name = if (year != null) "$title ($year)" else title,
            url = url,
            year = year,
            quality = qualityOf(url),
        )
    }

    /** A file to play, resolved to whatever archive.org serves it from. */
    internal data class Movie(
        val name: String,
        val url: String,
        val year: Int?,
        val quality: Int,
    )

    /** `movie.php` only redirects to the archive.org file, so the link points straight at it. */
    private suspend fun resolveFile(url: String): String =
        app.get(url, referer = "$MAIN_URL/", allowRedirects = false)
            .headers["location"]
            ?.takeIf { it.startsWith("http") }
            ?: url

    /** Fetches the sitemap once and reuses it, it is the same few thousand urls all day. */
    private suspend fun index(): List<Entry> = indexLock.withLock {
        cachedIndex?.takeIf { unixTimeMS - cachedAt < INDEX_TTL_MS }?.let { return@withLock it }

        val entries = parseSitemap(app.get(SITEMAP_URL).text)
        if (entries.isEmpty()) return@withLock cachedIndex.orEmpty()

        cachedIndex = entries
        cachedAt = unixTimeMS
        entries
    }

    /** One movie page out of the sitemap, its slug already cut the way titles are. */
    internal data class Entry(val slug: String, val tokens: List<String>)

    companion object {
        private const val MAIN_URL = "https://publicdomainmovie.net"
        private const val SITEMAP_URL = "$MAIN_URL/sitemap.xml"
        private const val INDEX_TTL_MS = 12 * 60 * 60 * 1000L

        /** How many pages one request may open, best match first. */
        private const val MAX_CANDIDATES = 3

        private val indexLock = Mutex()
        private var cachedIndex: List<Entry>? = null
        private var cachedAt = 0L

        private val LOCATION_REGEX = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>")
        private val RESOLUTION_REGEX = Regex("(\\d{3,4})p")

        /** Straight and curly apostrophes, which a slug spells by leaving them out. */
        private val APOSTROPHE_REGEX = Regex("['‘’]")

        /** Anything a slug turns into a separator. */
        private val SEPARATOR_REGEX = Regex("[^a-z0-9]+")

        /** Latin letters a slug spells without their accent, in the same order as [PLAIN]. */
        private const val ACCENTED = "àáâãäåèéêëìíîïòóôõöùúûüýÿçñ"
        private const val PLAIN = "aaaaaaeeeeiiiiooooouuuuyycn"

        /**
         * Slug parts that sit next to a title without changing which film it is. Numeric ones cover
         * both the year and the counter a duplicated slug ends with.
         */
        private val EXTRA_SLUG_TOKENS = setOf(
            "hd", "full", "movie", "film", "restored", "remastered", "color", "colorized",
        )

        /**
         * Every `/movie/` url in [xml]. Percent encoded slugs are dropped, they spell titles in
         * scripts no request is going to arrive in.
         */
        internal fun parseSitemap(xml: String): List<Entry> = LOCATION_REGEX
            .findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.contains("/movie/") }
            .map { it.substringAfterLast("/movie/").trim('/') }
            .filter { it.isNotEmpty() && !it.contains('%') }
            .distinct()
            .map { slug -> Entry(slug, slug.split('-').filter(String::isNotEmpty)) }
            .toList()

        /** The slugs worth opening for [request], best match first. */
        internal fun candidates(index: List<Entry>, request: SourceRequest): List<String> {
            val titles = listOfNotNull(request.title, request.originalTitle)
                .map(::tokenize)
                .filter { it.isNotEmpty() }
                .distinct()
            if (titles.isEmpty()) return emptyList()

            return index
                .mapNotNull { entry ->
                    titles.mapNotNull { title -> rank(entry.tokens, title, request.year) }
                        .minOrNull()
                        ?.let { rank -> rank to entry.slug }
                }
                .sortedBy { (rank, _) -> rank }
                .take(MAX_CANDIDATES)
                .map { (_, slug) -> slug }
        }

        /**
         * How well [slug] fits [title], lower being better, null when it is another film.
         *
         * A slug opens with the title, so whatever is left over decides: nothing at all is the
         * plain title, the release year is the site telling two films apart, and the rest is noise
         * that the movie page still has to confirm.
         */
        internal fun rank(slug: List<String>, title: List<String>, year: Int?): Int? {
            if (slug.size < title.size || slug.subList(0, title.size) != title) return null

            val extras = slug.drop(title.size)
            return when {
                extras.isEmpty() -> 0
                year != null && extras == listOf(year.toString()) -> 1
                extras.all { extra ->
                    extra.all(Char::isDigit) ||
                        extra.matches(RESOLUTION_REGEX) ||
                        extra in EXTRA_SLUG_TOKENS
                } -> 2
                else -> null
            }
        }

        /**
         * The films from [movies] that came out in [year], or all of them when none did.
         *
         * The year separates a film from its remakes, so it wins whenever the site actually has
         * the requested one. It does not get to veto, though: the site only holds films old enough
         * to have fallen out of copyright, so a request carrying the year of a remake would find
         * nothing at all, when the print it is a remake of is right there. The name of every link
         * carries the year of the film it plays, which is what tells the two apart.
         */
        internal fun ofYear(movies: List<Movie>, year: Int?): List<Movie> {
            if (year == null) return movies

            // Metadata providers disagree about release years, so a year off by one still counts.
            return movies.filter { it.year != null && abs(it.year - year) <= 1 }
                .ifEmpty { movies }
        }

        /**
         * A title cut into the parts a slug is built from, so "Charlie Chaplin's The Vagabond"
         * lines up with "charlie-chaplins-the-vagabond".
         */
        internal fun tokenize(title: String): List<String> = title
            .lowercase()
            .map { char -> ACCENTED.indexOf(char).let { if (it >= 0) PLAIN[it] else char } }
            .joinToString("")
            .replace(APOSTROPHE_REGEX, "")
            .split(SEPARATOR_REGEX)
            .filter { it.isNotEmpty() }

        /** Archive.org names its files after the resolution, when it is one worth naming. */
        internal fun qualityOf(url: String): Int =
            RESOLUTION_REGEX.find(url.substringAfterLast('/').lowercase())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: Qualities.Unknown.value
    }
}
