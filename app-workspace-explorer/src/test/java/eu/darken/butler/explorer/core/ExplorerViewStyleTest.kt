package eu.darken.butler.explorer.core

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class ExplorerViewStyleTest : BaseTest() {

    private val json = Json {
        encodeDefaults = true
    }

    @Test
    fun `serialize List with defaults`() {
        val style = ExplorerViewStyle.List()
        val serialized = json.encodeToString<ExplorerViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "comfortable"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize List with custom values`() {
        val style = ExplorerViewStyle.List(
            density = ExplorerViewStyle.List.Density.DETAILED,
        )
        val serialized = json.encodeToString<ExplorerViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "detailed"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Grid with defaults`() {
        val style = ExplorerViewStyle.Grid()
        val serialized = json.encodeToString<ExplorerViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "size": "medium"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Grid with custom values`() {
        val style = ExplorerViewStyle.Grid(
            size = ExplorerViewStyle.Grid.GridSize.SMALL,
        )
        val serialized = json.encodeToString<ExplorerViewStyle>(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "size": "small"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize List from JSON`() {
        val jsonString = """
            {
                "type": "list",
                "density": "compact"
            }
        """

        val style = json.decodeFromString<ExplorerViewStyle>(jsonString)

        style shouldBe ExplorerViewStyle.List(
            density = ExplorerViewStyle.List.Density.COMPACT,
        )
    }

    @Test
    fun `deserialize Grid from JSON`() {
        val jsonString = """
            {
                "type": "grid",
                "size": "large"
            }
        """

        val style = json.decodeFromString<ExplorerViewStyle>(jsonString)

        style shouldBe ExplorerViewStyle.Grid(
            size = ExplorerViewStyle.Grid.GridSize.LARGE,
        )
    }

    @Test
    fun `roundtrip List serialization`() {
        val original = ExplorerViewStyle.List(
            density = ExplorerViewStyle.List.Density.DETAILED,
        )

        val serialized = json.encodeToString<ExplorerViewStyle>(original)
        val deserialized = json.decodeFromString<ExplorerViewStyle>(serialized)

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Grid serialization`() {
        val original = ExplorerViewStyle.Grid(
            size = ExplorerViewStyle.Grid.GridSize.SMALL,
        )

        val serialized = json.encodeToString<ExplorerViewStyle>(original)
        val deserialized = json.decodeFromString<ExplorerViewStyle>(serialized)

        deserialized shouldBe original
    }

    @Test
    fun `deserialize with missing optional fields uses defaults`() {
        val jsonString = """
            {
                "type": "list"
            }
        """

        val style = json.decodeFromString<ExplorerViewStyle>(jsonString)

        style shouldBe ExplorerViewStyle.List()
    }

    @Test
    fun `deserialize List with partial fields`() {
        val jsonString = """
            {
                "type": "list",
                "density": "compact"
            }
        """

        val style = json.decodeFromString<ExplorerViewStyle>(jsonString)

        style shouldBe ExplorerViewStyle.List(
            density = ExplorerViewStyle.List.Density.COMPACT,
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

        val style = json.decodeFromString<ExplorerViewStyle>(jsonString)

        style shouldBe ExplorerViewStyle.Grid(
            size = ExplorerViewStyle.Grid.GridSize.LARGE,
        )
    }

    @Test
    fun `all List Density values roundtrip correctly`() {
        ExplorerViewStyle.List.Density.values().forEach { density ->
            val style = ExplorerViewStyle.List(density = density)
            val serialized = json.encodeToString<ExplorerViewStyle>(style)
            val deserialized = json.decodeFromString<ExplorerViewStyle>(serialized)

            (deserialized as ExplorerViewStyle.List).density shouldBe density
        }
    }

    @Test
    fun `all Grid GridSize values roundtrip correctly`() {
        ExplorerViewStyle.Grid.GridSize.values().forEach { size ->
            val style = ExplorerViewStyle.Grid(size = size)
            val serialized = json.encodeToString<ExplorerViewStyle>(style)
            val deserialized = json.decodeFromString<ExplorerViewStyle>(serialized)

            (deserialized as ExplorerViewStyle.Grid).size shouldBe size
        }
    }

    @Test
    fun `default returns List style`() {
        val style = ExplorerViewStyle.default()

        style shouldBe ExplorerViewStyle.List()
    }
}
