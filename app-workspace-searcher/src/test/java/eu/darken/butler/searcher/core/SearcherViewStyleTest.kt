package eu.darken.butler.searcher.core

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SearcherViewStyleTest : BaseTest() {

    private val json = Json {
        encodeDefaults = true
    }

    @Test
    fun `serialize List with defaults`() {
        val style = SearcherViewStyle.List()
        val serialized = json.encodeToString<SearcherViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "comfortable"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize List with custom values`() {
        val style = SearcherViewStyle.List(
            density = SearcherViewStyle.List.Density.COMPACT,
        )
        val serialized = json.encodeToString<SearcherViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "compact"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Grid with defaults`() {
        val style = SearcherViewStyle.Grid()
        val serialized = json.encodeToString<SearcherViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "size": "medium"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Grid with custom values`() {
        val style = SearcherViewStyle.Grid(
            size = SearcherViewStyle.Grid.GridSize.LARGE,
        )
        val serialized = json.encodeToString<SearcherViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "size": "large"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize List from JSON`() {
        val jsonString = """
            {
                "type": "list",
                "density": "detailed"
            }
        """

        val style = json.decodeFromString<SearcherViewStyle>(jsonString)

        style shouldBe SearcherViewStyle.List(
            density = SearcherViewStyle.List.Density.DETAILED,
        )
    }

    @Test
    fun `deserialize Grid from JSON`() {
        val jsonString = """
            {
                "type": "grid",
                "size": "small"
            }
        """

        val style = json.decodeFromString<SearcherViewStyle>(jsonString)

        style shouldBe SearcherViewStyle.Grid(
            size = SearcherViewStyle.Grid.GridSize.SMALL,
        )
    }

    @Test
    fun `roundtrip List serialization`() {
        val original = SearcherViewStyle.List(
            density = SearcherViewStyle.List.Density.COMPACT,
        )

        val serialized = json.encodeToString<SearcherViewStyle>(original)
        val deserialized = json.decodeFromString<SearcherViewStyle>(serialized)

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Grid serialization`() {
        val original = SearcherViewStyle.Grid(
            size = SearcherViewStyle.Grid.GridSize.LARGE,
        )

        val serialized = json.encodeToString<SearcherViewStyle>(original)
        val deserialized = json.decodeFromString<SearcherViewStyle>(serialized)

        deserialized shouldBe original
    }

    @Test
    fun `deserialize with missing optional fields uses defaults`() {
        val jsonString = """
            {
                "type": "list"
            }
        """

        val style = json.decodeFromString<SearcherViewStyle>(jsonString)

        style shouldBe SearcherViewStyle.List()
    }

    @Test
    fun `deserialize List with partial fields`() {
        val jsonString = """
            {
                "type": "list",
                "density": "compact"
            }
        """

        val style = json.decodeFromString<SearcherViewStyle>(jsonString)

        style shouldBe SearcherViewStyle.List(
            density = SearcherViewStyle.List.Density.COMPACT,
        )
    }

    @Test
    fun `deserialize Grid with partial fields`() {
        val jsonString = """
            {
                "type": "grid",
                "size": "large"
            }
        """

        val style = json.decodeFromString<SearcherViewStyle>(jsonString)

        style shouldBe SearcherViewStyle.Grid(
            size = SearcherViewStyle.Grid.GridSize.LARGE,
        )
    }

    @Test
    fun `all List Density values serialize correctly`() {
        SearcherViewStyle.List.Density.values().forEach { density ->
            val style = SearcherViewStyle.List(density = density)
            val serialized = json.encodeToString<SearcherViewStyle>(style)
            val deserialized = json.decodeFromString<SearcherViewStyle>(serialized)

            (deserialized as SearcherViewStyle.List).density shouldBe density
        }
    }

    @Test
    fun `all Grid GridSize values serialize correctly`() {
        SearcherViewStyle.Grid.GridSize.values().forEach { size ->
            val style = SearcherViewStyle.Grid(size = size)
            val serialized = json.encodeToString<SearcherViewStyle>(style)
            val deserialized = json.decodeFromString<SearcherViewStyle>(serialized)

            (deserialized as SearcherViewStyle.Grid).size shouldBe size
        }
    }

    @Test
    fun `default returns List style`() {
        val style = SearcherViewStyle.default()

        style shouldBe SearcherViewStyle.List()
    }
}
