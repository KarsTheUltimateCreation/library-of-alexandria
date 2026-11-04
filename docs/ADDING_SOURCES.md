# Adding a content provider

Providers are bundled inside the extension as implementations of
`alexandria.core.sources.SourceAdapter` (in `core/`). Installed Mihon/Tachimanga extensions
cannot be called from another extension, so a provider has to be ported into this interface.

## 1. Pick the provider

Prefer providers that are:

- reachable without a Cloudflare challenge or login (the core runs headless; there is no WebView in the
  aggregation path),
- stable in markup or, better, backed by a JSON API,
- not already covered (the extra value is in coverage, quality, or language).

A maintained [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) extension
(Apache-2.0) is usually the best starting point. Copy the **selectors and request logic**, not the class:
Keiyoushi sources depend on Keiyoushi's own `core` module and KSP code generation, which this
project does not use. Credit the origin in a KDoc comment and in `NOTICE`.

## 2. Implement the adapter

```kotlin
class ExampleAdapter(ctx: AdapterContext) : HtmlAdapter(ctx) {
    override val id = "example"                 // [a-z0-9_]{2,32}; stored in users' data, never rename
    override val name = "Example"
    override val baseUrl = "https://example.org"
    override val languages = setOf("en")        // null = multilingual, filtered per request
    override val limiter = RateLimiter.shared("example", 2, 1_000) // be polite

    override suspend fun search(query: String): List<SourceSeries> = ...
    override suspend fun details(series: SourceSeries): SourceSeries = ...   // alt titles, tracker ids, kind, year
    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> = ...
    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> = ...
    override fun seriesWebUrl(seriesId: String) = "$baseUrl/series/$seriesId"
}
```

Guidelines:

- `SourceSeries.id` / `SourceChapter.id` must be **stable** (a path or numeric id, not a full URL with
  tracking parameters). They are persisted in users' libraries.
- Put the provider's own label in `SourceChapter.label` untouched. If the provider exposes structured
  numbers (like MangaDex), also set `number` / `volume` as **strings** (`"10.10"` is not `"10.1"`).
- Fill `ExternalIds` whenever the provider links to AniList / MyAnimeList / MangaUpdates / MangaDex;
  `ExternalLinkIds.from(urls)` extracts them. Shared ids make identity matching certain.
- Set `kind` (manga / manhwa / novel …) when known; novels are never merged with comics.
- Return per-image headers (`Referer`) through `imageHeaders()`; they are applied both for reading and
  for quality probes.
- Throw on failure (HTTP errors become `HttpError`); the aggregator records provider health and falls back.

## 3. Register it

Add it to `SourceRegistry.defaults(...)`. It automatically appears in *Enabled providers*,
*Provider order* and *Preferred source* settings.

## 4. Test it

- Add parsing tests with small **hand-written** HTML/JSON snippets that mimic the structure (do not
  commit copyrighted pages).
- Add it to `LiveIntegrationTest` and run `LOA_LIVE=1 ./gradlew :core:test --tests '*Live*'` to verify
  search → details → chapters → pages against the real site.
- Bump `extVersionCode` in `extension/build.gradle.kts`.
