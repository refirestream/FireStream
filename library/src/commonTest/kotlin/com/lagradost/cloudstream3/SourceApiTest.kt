@file:OptIn(ExperimentalUuidApi::class)

package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceApiTest {

    private class TestSource(
        override val id: String,
        override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries),
        val onLoad: suspend (SourceRequest) -> Boolean = { true },
    ) : SourceApi() {
        override val name = id

        override suspend fun loadLinks(
            request: SourceRequest,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean = onLoad(request)
    }

    @AfterTest
    fun clearSources() {
        SourceApiHolder.allSources.clear()
    }

    private fun request(tvType: TvType = TvType.Movie) = SourceRequest(
        title = "Some Movie",
        tvType = tvType,
    )

    @Test
    fun sourceRequestSurvivesAJsonRoundTrip() {
        val request = SourceRequest(
            title = "Arcane",
            tvType = TvType.TvSeries,
            originalTitle = "Arcane",
            year = 2021,
            airedYear = 2024,
            season = 2,
            episode = 3,
            imdbId = "tt11126994",
            tmdbId = 94605,
            anilistId = 129890,
            malId = 41982,
            kitsuId = "43152",
        )

        assertEquals(request, tryParseJson<SourceRequest>(request.toJson()))
    }

    @Test
    fun sourceRequestRoundTripKeepsNullsNull() {
        val request = request()
        val parsed = tryParseJson<SourceRequest>(request.toJson())

        assertEquals(request, parsed)
        assertNull(parsed?.imdbId)
        assertNull(parsed?.season)
    }

    @Test
    fun registerSourceRejectsAnAlreadyTakenId() {
        assertTrue(SourceApiHolder.registerSource(TestSource("duplicated")))
        assertFalse(SourceApiHolder.registerSource(TestSource("duplicated")))

        assertEquals(1, SourceApiHolder.allSources.size)
    }

    @Test
    fun getSourceFromIdFindsARegisteredSource() {
        val source = TestSource("findable")
        SourceApiHolder.registerSource(source)

        assertEquals(source, SourceApiHolder.getSourceFromId("findable"))
        assertNull(SourceApiHolder.getSourceFromId("never-registered"))
        assertNull(SourceApiHolder.getSourceFromId(null))
    }

    @Test
    fun sourcesForSkipsUnsupportedTypes() {
        val movies = TestSource("movies", supportedTypes = setOf(TvType.Movie))
        val anime = TestSource("anime", supportedTypes = setOf(TvType.Anime))
        SourceApiHolder.registerSource(movies)
        SourceApiHolder.registerSource(anime)

        assertEquals(listOf(movies), SourceApiHolder.sourcesFor(request(TvType.Movie)))
        assertEquals(listOf(anime), SourceApiHolder.sourcesFor(request(TvType.Anime)))
        assertEquals(emptyList(), SourceApiHolder.sourcesFor(request(TvType.Live)))
    }

    @Test
    fun loadLinksFromSourcesRunsEverySupportedSource() = runTest {
        val called = mutableListOf<String>()
        SourceApiHolder.registerSource(TestSource("first") { called.add("first"); true })
        SourceApiHolder.registerSource(TestSource("second") { called.add("second"); true })
        SourceApiHolder.registerSource(
            TestSource("anime-only", supportedTypes = setOf(TvType.Anime)) {
                called.add("anime-only")
                true
            }
        )

        assertTrue(SourceApiHolder.loadLinksFromSources(request(), {}, {}))
        assertEquals(listOf("first", "second"), called.sorted())
    }

    @Test
    fun loadLinksFromSourcesKeepsGoingWhenOneSourceThrows() = runTest {
        var reached = false
        SourceApiHolder.registerSource(TestSource("broken") { error("this source is broken") })
        SourceApiHolder.registerSource(TestSource("working") { reached = true; true })

        assertTrue(SourceApiHolder.loadLinksFromSources(request(), {}, {}))
        assertTrue(reached)
    }

    @Test
    fun loadLinksFromSourcesIsFalseWhenNothingIsFound() = runTest {
        SourceApiHolder.registerSource(TestSource("empty") { false })
        SourceApiHolder.registerSource(TestSource("broken") { error("this source is broken") })

        assertFalse(SourceApiHolder.loadLinksFromSources(request(), {}, {}))
    }

    @Test
    fun loadLinksFromSourcesIsFalseWithoutAnySource() = runTest {
        assertFalse(SourceApiHolder.loadLinksFromSources(request(), {}, {}))
    }

    @Test
    fun loadLinksFromSourcesForwardsWhatSourcesEmit() = runTest {
        SourceApiHolder.registerSource(
            object : SourceApi() {
                override val id = "emitting"
                override suspend fun loadLinks(
                    request: SourceRequest,
                    subtitleCallback: (SubtitleFile) -> Unit,
                    callback: (ExtractorLink) -> Unit,
                ): Boolean {
                    callback(newExtractorLink("emitting", "emitting", "https://test.com/video.mp4"))
                    subtitleCallback(newSubtitleFile("en", "https://test.com/subtitle.srt"))
                    return true
                }
            }
        )

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        assertTrue(
            SourceApiHolder.loadLinksFromSources(request(), { subtitles.add(it) }, { links.add(it) })
        )

        assertEquals(listOf("https://test.com/video.mp4"), links.map { it.url })
        assertEquals(listOf("https://test.com/subtitle.srt"), subtitles.map { it.url })
    }

    @Test
    fun loadLinksFromSourcesPutsTheSourceNameInFrontOfTheLink() = runTest {
        SourceApiHolder.registerSource(emittingSource("emitting", "MySource"))

        val links = mutableListOf<ExtractorLink>()
        SourceApiHolder.loadLinksFromSources(request(), {}, { links.add(it) })

        assertEquals(listOf("[MySource] Night.Living.Dead 4k French"), links.map { it.name })
    }

    @Test
    fun linkLabelFallsBackToTheSourceIdWhenItHasNoName() = runTest {
        SourceApiHolder.registerSource(emittingSource("nameless-source"))

        val links = mutableListOf<ExtractorLink>()
        SourceApiHolder.loadLinksFromSources(request(), {}, { links.add(it) })

        assertEquals(listOf("[nameless-source] Night.Living.Dead 4k French"), links.map { it.name })
    }

    @Test
    fun labellingALinkKeepsEverythingElseAboutIt() = runTest {
        SourceApiHolder.registerSource(emittingSource("emitting", "MySource"))

        val links = mutableListOf<ExtractorLink>()
        SourceApiHolder.loadLinksFromSources(request(), {}, { links.add(it) })

        val link = links.single()
        assertEquals("MyExtractor", link.source)
        assertEquals("https://test.com/video.mp4", link.url)
        assertEquals("https://test.com/", link.referer)
        assertEquals(Qualities.P2160.value, link.quality)
        assertEquals(ExtractorLinkType.M3U8, link.type)
        assertEquals(mapOf("Cookie" to "test"), link.headers)
        assertEquals("extractor-data", link.extractorData)
    }

    @Test
    fun labellingKeepsAPlayListLinkAPlayList() = runTest {
        val playlist = listOf(PlayListItem("https://test.com/part1.mp4", 0))
        SourceApiHolder.registerSource(
            source("playlist", "MySource") { _, _, callback ->
                callback(
                    ExtractorLinkPlayList(
                        source = "MyExtractor",
                        name = "Night.Living.Dead 4k French",
                        playlist = playlist,
                        referer = "https://test.com/",
                        quality = Qualities.P2160.value,
                    )
                )
                true
            }
        )

        val links = mutableListOf<ExtractorLink>()
        SourceApiHolder.loadLinksFromSources(request(), {}, { links.add(it) })

        val link = links.single() as ExtractorLinkPlayList
        assertEquals("[MySource] Night.Living.Dead 4k French", link.name)
        assertEquals(playlist, link.playlist)
    }

    @Test
    fun labellingKeepsADrmLinkDrm() = runTest {
        SourceApiHolder.registerSource(
            source("drm", "MySource") { _, _, callback ->
                callback(
                    newDrmExtractorLink(
                        "MyExtractor",
                        "Night.Living.Dead 4k French",
                        "https://test.com/video.mpd",
                        ExtractorLinkType.DASH,
                        CLEARKEY_DRM_UUID,
                    ) {
                        this.kid = "test-kid"
                        this.key = "test-key"
                        this.licenseUrl = "https://test.com/license"
                    }
                )
                true
            }
        )

        val links = mutableListOf<ExtractorLink>()
        SourceApiHolder.loadLinksFromSources(request(), {}, { links.add(it) })

        val link = links.single() as DrmExtractorLink
        assertEquals("[MySource] Night.Living.Dead 4k French", link.name)
        assertEquals("test-kid", link.kid)
        assertEquals("test-key", link.key)
        assertEquals("https://test.com/license", link.licenseUrl)
        assertEquals(CLEARKEY_DRM_UUID, link.uuid)
    }

    private fun source(
        id: String,
        name: String? = null,
        onLoad: suspend (SourceRequest, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean,
    ) = object : SourceApi() {
        override val id = id
        override val name = name ?: super.name

        override suspend fun loadLinks(
            request: SourceRequest,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean = onLoad(request, subtitleCallback, callback)
    }

    /** A source handing back one fully filled in link, to check what labelling does to it. */
    private fun emittingSource(id: String, name: String? = null) =
        source(id, name) { _, _, callback ->
            callback(
                newExtractorLink(
                    "MyExtractor",
                    "Night.Living.Dead 4k French",
                    "https://test.com/video.mp4",
                    ExtractorLinkType.M3U8,
                ) {
                    this.referer = "https://test.com/"
                    this.quality = Qualities.P2160.value
                    this.headers = mapOf("Cookie" to "test")
                    this.extractorData = "extractor-data"
                }
            )
            true
        }
}
