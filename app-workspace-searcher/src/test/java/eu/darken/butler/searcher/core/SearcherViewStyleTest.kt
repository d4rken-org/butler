package eu.darken.butler.searcher.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SearcherViewStyleTest : BaseTest() {

    private val json = Json {
        encodeDefaults = true
    }

    /** Legacy payloads carry keys this type no longer has, which only the app's own Json tolerates. */
    private val productionJson = SerializationCommonModule().json()

    @Test
    fun `serialize with defaults`() {
        val serialized = json.encodeToString(SearcherViewStyle())

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "comfortable"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize with custom values`() {
        val style = SearcherViewStyle(
            mode = SearcherViewStyle.Mode.GRID,
            density = SearcherViewStyle.Density.COMPACT,
        )
        val serialized = json.encodeToString(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "density": "compact"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize from JSON`() {
        val jsonString = """
            {
                "type": "list",
                "density": "detailed"
            }
        """

        json.decodeFromString<SearcherViewStyle>(jsonString) shouldBe SearcherViewStyle(
            mode = SearcherViewStyle.Mode.LIST,
            density = SearcherViewStyle.Density.DETAILED,
        )
    }

    @Test
    fun `deserialize with missing optional fields uses defaults`() {
        json.decodeFromString<SearcherViewStyle>("""{"type":"list"}""") shouldBe SearcherViewStyle()
        json.decodeFromString<SearcherViewStyle>("{}") shouldBe SearcherViewStyle()
    }

    /** A list user keeps both the mode and the density they had. */
    @Test
    fun `a legacy list payload keeps its mode and density`() {
        val legacy = """
            {
                "type": "list",
                "density": "comfortable"
            }
        """

        productionJson.decodeFromString<SearcherViewStyle>(legacy) shouldBe SearcherViewStyle(
            mode = SearcherViewStyle.Mode.LIST,
            density = SearcherViewStyle.Density.COMFORTABLE,
        )
    }

    /** A grid user keeps the grid; the retired tile size has no density to map onto. */
    @Test
    fun `a legacy grid payload keeps grid and falls back to the default density`() {
        val legacy = """
            {
                "type": "grid",
                "size": "medium"
            }
        """

        productionJson.decodeFromString<SearcherViewStyle>(legacy) shouldBe SearcherViewStyle(
            mode = SearcherViewStyle.Mode.GRID,
            density = SearcherViewStyle.Density.COMFORTABLE,
        )
    }

    @Test
    fun `all modes and densities roundtrip correctly`() {
        SearcherViewStyle.Mode.entries.forEach { mode ->
            SearcherViewStyle.Density.entries.forEach { density ->
                val original = SearcherViewStyle(mode = mode, density = density)

                json.decodeFromString<SearcherViewStyle>(json.encodeToString(original)) shouldBe original
            }
        }
    }

    @Test
    fun `default is a comfortable list`() {
        SearcherViewStyle.default() shouldBe SearcherViewStyle(
            mode = SearcherViewStyle.Mode.LIST,
            density = SearcherViewStyle.Density.COMFORTABLE,
        )
    }
}
