# Adding a content provider

Providers are bundled inside the extension as implementations of
`alexandria.core.sources.SourceAdapter` (in `core/`). Installed Mihon/Tachimanga extensions
cannot be called from another extension, so a provider has to be ported into this interface.

## 1. Pick the provider

Prefer providers that are:

- reachable without a Cloudflare challenge or login (the core runs headless; there is no WebView in the
  aggregation path),
- stable in markup or, better, backed by a JSON API,
- English, and not already covered (the extension is English-only; the extra value is in coverage or quality).

A maintained [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) extension
(Apache-2.0) is usually the best starting point. Copy the **selectors and request logic**, not the class:
Keiyoushi sources depend on Keiyoushi's own `core` module and KSP code generation, which this
project does not use. Credit the origin in a KDoc comment and in `NOTICE`.

## 2. Implement the adapter

```kotlin
class ExampleAdapter(ctx: AdapterContext) : HtmlAdapter(ctx) {
    override val id = "loa_example"             // [a-z0-9_]{2,32}; new ids use the "loa_" namespace; stored in users' data, never rename
    override val name = "Example"
    override val baseUrl = "https://example.org"
    override val languages = setOf("en")        // the extension is English-only
    override val limiter = RateLimiter.shared("example", 2, 1_000) // be polite

    override suspend fun search(query: String): List<SourceSeries> = ...
    override suspend fun details(series: SourceSeries): SourceSeries = ...   // alt titles, tracker ids, kind, year
    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> = ...
    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> = ...
    override fun seriesWebUrl(seriesId: String) = "$baseUrl/series/$seriesId"
}
```

Classify the provider so it is only asked when it can help (this is what keeps opening a series fast):

- `adult = true` – adult content; searched to link a catalogue series only when that series is adult.
- `role = ProviderRole.GALLERY` – single galleries (doujinshi): standalone search results with one chapter
  (`Galleries.chapter`), never searched to link catalogue series.
- `standaloneResults = false` – hits are only used to link catalogue entries, never listed on their own.
- Pages that must be fetched to learn the image address: return `ReaderPage(lazy = true)` and implement
  `resolveImage` (resolved one page at a time while reading).

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
- Make every request through `ctx.client.fetch` / `fetchText` with the provider's `limiter`. That is what applies the
  shared pacing: rate limits, `Retry-After` cool-downs shared by all callers, per-request network timeouts, bounded
  retries and cancellation while the body downloads. Calling `client.newCall(...)` directly bypasses all of it.
- Set `specialties` when the site mostly carries one content type (e.g. manhwa); it only orders discovery.
- Avoid features the portable core cannot provide on every host: Android `Bitmap` work (descrambling, merging),
  WebView, JavaScript evaluation. If some chapters need them, throw for those chapters so fallback takes over
  (see `AsuraScansAdapter`).

## 3. Register it

Add it to `SourceRegistry.defaults(...)`. It automatically appears in *Enabled providers*,
*Provider order* and *Preferred source* settings.

## 4. Test it

- Add parsing tests with small **hand-written** HTML/JSON snippets that mimic the structure (do not
  commit copyrighted pages).
- Add it to `LiveIntegrationTest` and run `LOA_LIVE=1 ./gradlew :core:test --tests '*Live*'` to verify
  search → details → chapters → pages against the real site.
- Bump `extVersionCode` in `extension/build.gradle.kts`.
