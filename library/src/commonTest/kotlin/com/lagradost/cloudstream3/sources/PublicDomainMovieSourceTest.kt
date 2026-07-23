package com.lagradost.cloudstream3.sources

import com.lagradost.cloudstream3.SourceRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.candidates
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.ofYear
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.parseSitemap
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.qualityOf
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.rank
import com.lagradost.cloudstream3.sources.PublicDomainMovieSource.Companion.tokenize
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicDomainMovieSourceTest {

    /** Cut down from the real sitemap, keeping the shapes the slugs actually come in. */
    private val sitemap = """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
        <url><loc>http://publicdomainmovies.net/</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/galloping-romeo</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/charlie-chaplins-the-vagabond</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/detour</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/detour-0</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/the-general-1926</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/topper-returns-720p-1941</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/the-brain-that-wouldnt-die-0</loc></url>
        <url><loc>http://publicdomainmovies.net/movie/zwenigora-%D0%B7%D0%B2%D0%B5%D0%BD</loc></url>
        <url><loc>http://publicdomainmovies.net/subject/western-15</loc></url>
        </urlset>
    """.trimIndent()

    private val index = parseSitemap(sitemap)

    private fun request(title: String, year: Int? = null, originalTitle: String? = null) =
        SourceRequest(
            title = title,
            tvType = TvType.Movie,
            year = year,
            originalTitle = originalTitle,
        )

    @Test
    fun sitemapKeepsMoviesOnly() {
        val slugs = index.map { it.slug }

        assertTrue(slugs.contains("galloping-romeo"))
        assertTrue(slugs.none { it.contains("western") })
        // Percent encoded slugs spell titles no request arrives in.
        assertTrue(slugs.none { it.startsWith("zwenigora") })
    }

    @Test
    fun sitemapSplitsSlugsIntoTheirParts() {
        assertEquals(
            listOf("the", "general", "1926"),
            index.first { it.slug == "the-general-1926" }.tokens,
        )
    }

    @Test
    fun titlesAreCutTheWaySlugsAre() {
        assertEquals(listOf("galloping", "romeo"), tokenize("Galloping Romeo"))
        assertEquals(
            listOf("charlie", "chaplins", "the", "vagabond"),
            tokenize("Charlie Chaplin's The Vagabond"),
        )
        assertEquals(listOf("the", "brain", "that", "wouldnt", "die"), tokenize("The Brain That Wouldn't Die"))
        assertEquals(listOf("a", "corner", "in", "wheat"), tokenize("À Corner in Wheat"))
        assertEquals(emptyList<String>(), tokenize("  "))
    }

    @Test
    fun theBareTitleRanksAboveWhatFollowsIt() {
        val title = listOf("topper", "returns")

        assertEquals(0, rank(title, title, 1941))
        assertEquals(1, rank(title + "1941", title, 1941))
        assertEquals(2, rank(title + listOf("720p", "1941"), title, 1941))
        assertEquals(2, rank(title + "0", title, null))
    }

    @Test
    fun anotherFilmDoesNotRank() {
        assertNull(rank(listOf("detour", "to", "nowhere"), listOf("detour"), null))
        assertNull(rank(listOf("detour"), listOf("detour", "to", "nowhere"), null))
        assertNull(rank(listOf("the", "general", "1926"), listOf("general"), 1926))
    }

    @Test
    fun theMovieIsFoundByItsTitle() {
        assertEquals(listOf("galloping-romeo"), candidates(index, request("Galloping Romeo", 1933)))
    }

    @Test
    fun everySpellingOfATitleIsTried() {
        assertEquals(
            listOf("detour", "detour-0"),
            candidates(index, request("Nothing Like This", originalTitle = "Detour")),
        )
    }

    @Test
    fun aTitleTheSiteDoesNotHaveFindsNothing() {
        assertEquals(emptyList<String>(), candidates(index, request("Some Film Nobody Uploaded", 1962)))
    }

    @Test
    fun onlyAHandfulOfPagesAreOpened() {
        val many = (1..10).map { PublicDomainMovieSource.Entry("detour-$it", listOf("detour", "$it")) }

        assertEquals(3, candidates(many, request("Detour")).size)
    }

    private fun movie(year: Int?) = PublicDomainMovieSource.Movie(
        name = "Night Of The Living Dead ($year)",
        url = "https://archive.org/download/notld$year/notld.mp4",
        year = year,
        quality = Qualities.Unknown.value,
    )

    @Test
    fun theRequestedYearWinsOverARemake() {
        val movies = listOf(movie(1968), movie(1990))

        assertEquals(listOf(movie(1990)), ofYear(movies, 1990))
        // Release years disagree across metadata providers, so one off still counts.
        assertEquals(listOf(movie(1968)), ofYear(movies, 1969))
    }

    @Test
    fun aYearTheSiteCannotHaveStillPlaysWhatItDoes() {
        // The site only holds films old enough to have fallen out of copyright, so the year of a
        // 2026 remake matches nothing, and vetoing on it would drop the print it remakes.
        val movies = listOf(movie(1968))

        assertEquals(movies, ofYear(movies, 2026))
        assertEquals(movies, ofYear(movies, null))
        // A page without a year is still the only thing on offer.
        assertEquals(listOf(movie(null)), ofYear(listOf(movie(null)), 1968))
    }

    @Test
    fun qualityComesFromTheFileName() {
        assertEquals(720, qualityOf("https://archive.org/download/TopperReturns720p1941/TopperReturns720p.mp4"))
        assertEquals(
            Qualities.Unknown.value,
            qualityOf("https://archive.org/download/GallopingRomeo/GallopingRomeo_512kb.mp4"),
        )
    }
}
