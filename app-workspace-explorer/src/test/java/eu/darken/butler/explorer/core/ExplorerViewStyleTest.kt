package eu.darken.butler.explorer.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class ExplorerViewStyleTest : BaseTest() {

    private val json = Json {
        encodeDefaults = true
    }

    /** Legacy payloads carry keys this type no longer has, which only the app's own Json tolerates. */
    private val productionJson = SerializationCommonModule().json()

    @Test
    fun `serialize with defaults`() {
        val serialized = json.encodeToString(ExplorerViewStyle())

        serialized.toComparableJson() shouldBe """
            {
                "type": "list",
                "density": "comfortable",
                "showhidden": true
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize with custom values`() {
        val style = ExplorerViewStyle(
            mode = ExplorerViewStyle.Mode.GRID,
            density = ExplorerViewStyle.Density.DETAILED,
            showHidden = false,
        )
        val serialized = json.encodeToString(style)

        serialized.toComparableJson() shouldBe """
            {
                "type": "grid",
                "density": "detailed",
                "showhidden": false
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize from JSON`() {
        val jsonString = """
            {
                "type": "grid",
                "density": "compact",
                "showhidden": false
            }
        """

        json.decodeFromString<ExplorerViewStyle>(jsonString) shouldBe ExplorerViewStyle(
            mode = ExplorerViewStyle.Mode.GRID,
            density = ExplorerViewStyle.Density.COMPACT,
            showHidden = false,
        )
    }

    @Test
    fun `deserialize with missing optional fields uses defaults`() {
        json.decodeFromString<ExplorerViewStyle>("""{"type":"list"}""") shouldBe ExplorerViewStyle()
        json.decodeFromString<ExplorerViewStyle>("{}") shouldBe ExplorerViewStyle()
    }

    /** Nobody has ever chosen to hide anything, so a payload from before the switch shows everything. */
    @Test
    fun `a payload without the hidden-files key shows hidden files`() {
        val stored = """
            {
                "type": "grid",
                "density": "compact"
            }
        """

        productionJson.decodeFromString<ExplorerViewStyle>(stored).showHidden shouldBe true
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

        productionJson.decodeFromString<ExplorerViewStyle>(legacy) shouldBe ExplorerViewStyle(
            mode = ExplorerViewStyle.Mode.LIST,
            density = ExplorerViewStyle.Density.COMFORTABLE,
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

        productionJson.decodeFromString<ExplorerViewStyle>(legacy) shouldBe ExplorerViewStyle(
            mode = ExplorerViewStyle.Mode.GRID,
            density = ExplorerViewStyle.Density.COMFORTABLE,
        )
    }

    @Test
    fun `all modes and densities roundtrip correctly`() {
        ExplorerViewStyle.Mode.entries.forEach { mode ->
            ExplorerViewStyle.Density.entries.forEach { density ->
                listOf(true, false).forEach { showHidden ->
                    val original = ExplorerViewStyle(mode = mode, density = density, showHidden = showHidden)

                    json.decodeFromString<ExplorerViewStyle>(json.encodeToString(original)) shouldBe original
                }
            }
        }
    }

    @Test
    fun `default is a comfortable list`() {
        ExplorerViewStyle.default() shouldBe ExplorerViewStyle(
            mode = ExplorerViewStyle.Mode.LIST,
            density = ExplorerViewStyle.Density.COMFORTABLE,
            showHidden = true,
        )
    }
}
