package eu.darken.butler.apps.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class AppsArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = AppsArguments.Default()
        val serialized = json.encodeToJsonElement<AppsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with filterConfig includes values`() {
        val args = AppsArguments.Default(
            filterConfig = TagFilterConfig(
                includeTags = setOf(AppTag.System),
                excludeTags = setOf(AppTag.Disabled),
            ),
        )
        val serialized = json.encodeToJsonElement<AppsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filterConfig": {
                    "includeTags": [{"type": "system"}],
                    "excludeTags": [{"type": "disabled"}]
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with all settings`() {
        val args = AppsArguments.Default(
            filterConfig = TagFilterConfig(),
            sortSettings = SortSettings(mode = SortSettings.Mode.SIZE, reversed = true),
            viewStyle = AppsViewStyle(mode = AppsViewStyle.Mode.GRID),
        )
        val serialized = json.encodeToJsonElement<AppsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filterConfig": {
                    "includeTags": [],
                    "excludeTags": []
                },
                "sortSettings": {
                    "mode": "SIZE",
                    "reversed": true
                },
                "viewStyle": {
                    "type": "grid",
                    "density": "comfortable"
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments"
            }
        """

        val args = json.decodeFromString<AppsArguments>(jsonString)

        args shouldBe AppsArguments.Default()
    }

    @Test
    fun `deserialize Default with settings from JSON`() {
        val jsonString = """
            {
                "type": "arguments",
                "sortSettings": {
                    "mode": "NAME",
                    "reversed": false
                },
                "viewStyle": {
                    "type": "list",
                    "density": "comfortable"
                }
            }
        """

        val args = json.decodeFromString<AppsArguments>(jsonString)

        args shouldBe AppsArguments.Default(
            sortSettings = SortSettings(),
            viewStyle = AppsViewStyle(),
        )
    }

    /** What a version that stored a sealed hierarchy wrote; a held tab must survive reading it. */
    @Test
    fun `deserialize Default with a legacy grid view style`() {
        val jsonString = """
            {
                "type": "arguments",
                "viewStyle": {
                    "type": "grid",
                    "size": "large"
                }
            }
        """

        val args = json.decodeFromString<AppsArguments>(jsonString)

        args shouldBe AppsArguments.Default(
            viewStyle = AppsViewStyle(mode = AppsViewStyle.Mode.GRID),
        )
    }

    @Test
    fun `roundtrip Default serialization with settings`() {
        val original = AppsArguments.Default(
            filterConfig = TagFilterConfig(includeTags = setOf(AppTag.UserApp)),
            sortSettings = SortSettings(mode = SortSettings.Mode.UPDATE_DATE),
            viewStyle = AppsViewStyle(
                mode = AppsViewStyle.Mode.GRID,
                density = AppsViewStyle.Density.DETAILED,
            ),
        )

        val serialized = json.encodeToJsonElement<AppsArguments>(original)
        val deserialized = json.decodeFromString<AppsArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `backwards compatibility - deserialize without new fields`() {
        // Old workspaces may not have filterConfig, sortSettings, viewStyle
        val jsonString = """
            {
                "type": "arguments"
            }
        """

        val args = json.decodeFromString<AppsArguments>(jsonString)

        args shouldBe AppsArguments.Default(
            filterConfig = null,
            sortSettings = null,
            viewStyle = null,
        )
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : AppsWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: AppsArguments): AppsWorkspace = error("unused")
        }
        val original = AppsArguments.Default(
            filterConfig = TagFilterConfig(includeTags = setOf(AppTag.UserApp)),
            sortSettings = SortSettings(mode = SortSettings.Mode.SIZE, reversed = true),
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<AppsArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
