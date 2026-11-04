package alexandria.core.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals

class MetadataTitleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = MangaBakaClient(OkHttpClient(), json, "test")

    private fun parse(titles: String) = client.parseSeries(
        json.parseToJsonElement(
            """{"id":604971,"title":"Yongsaneun Swil Su Eopda","native_title":"용사는 쉴 수 없다",
               "romanized_title":"Yongsaneun Swil Su Eopda","type":"manhwa","titles":$titles}""",
        ).jsonObject,
    )

    @Test
    fun `english title is shown instead of the romanized main title`() {
        // Shape of the live MangaBaka record for "The Hero Cannot Rest".
        val m = parse(
            """[{"language":"ko","title":"용사는 쉴 수 없다","is_primary":true},
                {"language":"es","title":"El héroe no puede descansar","is_primary":true},
                {"language":"en","title":"Heroes Never Rest","is_primary":false},
                {"language":"en","title":"The Hero Cannot Rest","is_primary":true},
                {"language":"ko-Latn","title":"Yongsaneun Swil Su Eopda","is_primary":true}]""",
        )
        assertEquals("The Hero Cannot Rest", m.displayTitle)
        assertEquals("Yongsaneun Swil Su Eopda", m.title) // kept for matching and search queries
    }

    @Test
    fun `all caps english stylings use the normal-case variant when there is one`() {
        val m = alexandria.core.model.SeriesMetadata(provider = "mangabaka", title = "One Piece", englishTitle = "ONE PIECE")
        assertEquals("One Piece", m.displayTitle)
        val alt = alexandria.core.model.SeriesMetadata(provider = "mangabaka", title = "Shingeki no Kyojin", englishTitle = "ATTACK ON TITAN", altTitles = listOf("Attack on Titan"))
        assertEquals("Attack on Titan", alt.displayTitle)
        // No normal-case variant: keep the English title as is.
        assertEquals("DEMON SLAYER", alexandria.core.model.SeriesMetadata(provider = "mangabaka", title = "Kimetsu no Yaiba", englishTitle = "DEMON SLAYER").displayTitle)
    }

    @Test
    fun `falls back to any english title, then the main title`() {
        assertEquals("Heroes Never Rest", parse("""[{"language":"en","title":"Heroes Never Rest","is_primary":false}]""").displayTitle)
        assertEquals("Yongsaneun Swil Su Eopda", parse("""[{"language":"ko","title":"용사는 쉴 수 없다","is_primary":true}]""").displayTitle)
    }
}
