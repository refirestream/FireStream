# CLAUDE.md

## Adding a source

A source answers "given a title and its ids, what can be played". It does not browse or
search — that is a `MainAPI`. See `library/.../cloudstream3/SourceApi.kt`.

Subclass `SourceApi` in a plugin:

```kotlin
class MySource : SourceApi() {
    override val id = "my-source"          // unique, settings are keyed by it, never rename
    override val name = "MySource"         // shown in the UI, rename freely
    override val sourceType = SourceType.Direct
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        request: SourceRequest,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val imdbId = request.imdbId ?: return false
        // scrape, then emit as you find things
        callback(newExtractorLink("MySource", "Some Movie 1080p", url))
        return true
    }
}
```

Register it in the plugin's `load()`:

```kotlin
override fun load(context: Context) {
    registerSourceAPI(MySource())
}
```

Then:

- `SourceRequest` carries `title`, `originalTitle`, `year`, `airedYear`, `season`, `episode`,
  `imdbId`, `tmdbId`, `anilistId`, `malId`, `kitsuId`. Use what you need, ignore the rest.
- Every registered source runs, concurrently. No priority, no enabled state. `supportedTypes` is
  the only filter, so no need to re-check the type inside `loadLinks`.
- Do not prefix link names with the source name, `SourceApiHolder` does it: `[MySource] Some Movie 1080p`.
- Throwing is contained to your source, but return `false` rather than throwing when you simply
  found nothing.
- `SourceApiHolder.allSources` is the registry, unload strips sources by `sourcePlugin`.

Tests live in `library/src/commonTest/.../SourceApiTest.kt`, run with
`./gradlew :library:jvmTest --tests "com.lagradost.cloudstream3.SourceApiTest"`.
