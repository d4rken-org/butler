package eu.darken.butler.editor.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class EditorArgumentsSerializationTest : BaseTest() {

    private val json = Json(SerializationCommonModule().json()) {
        serializersModule = SerializersModule {
            include(SerializationCommonModule().json().serializersModule)
            polymorphic(APath::class) {
                subclass(LocalPath::class)
                subclass(SAFPath::class)
            }
        }
    }

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = EditorArguments.Default()
        val serialized = json.encodeToJsonElement<EditorArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with filePath includes type discriminator`() {
        val args = EditorArguments.Default(
            filePath = LocalPath.build("/sdcard/test.txt"),
        )
        val serialized = json.encodeToJsonElement<EditorArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filePath": {
                    "type": "LOCAL",
                    "file": "/sdcard/test.txt"
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments",
                "filePath": {
                    "type": "LOCAL",
                    "file": "/data/local/tmp/config.json"
                }
            }
        """

        val args = json.decodeFromString<EditorArguments>(jsonString)

        args shouldBe EditorArguments.Default(
            filePath = LocalPath.build("/data/local/tmp/config.json"),
        )
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = EditorArguments.Default(
            filePath = LocalPath.build("/sdcard/document.md"),
        )

        val serialized = json.encodeToJsonElement<EditorArguments>(original)
        val deserialized = json.decodeFromString<EditorArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Default with cursor and scroll position`() {
        val original = EditorArguments.Default(
            filePath = LocalPath.build("/sdcard/document.md"),
            cursorLine = 42,
            cursorColumn = 10,
            scrollToLine = 35,
        )

        val serialized = json.encodeToJsonElement<EditorArguments>(original)
        val deserialized = json.decodeFromString<EditorArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `serialize Default with cursor and scroll position`() {
        val args = EditorArguments.Default(
            filePath = LocalPath.build("/sdcard/test.txt"),
            cursorLine = 100,
            cursorColumn = 25,
            scrollToLine = 90,
        )
        val serialized = json.encodeToJsonElement<EditorArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filePath": {
                    "type": "LOCAL",
                    "file": "/sdcard/test.txt"
                },
                "cursorLine": 100,
                "cursorColumn": 25,
                "scrollToLine": 90
            }
        """.toComparableJson()
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : EditorWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: EditorArguments): EditorWorkspace = error("unused")
        }
        val original = EditorArguments.Default(
            filePath = LocalPath.build("/sdcard/document.md"),
            cursorLine = 42,
            cursorColumn = 10,
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<EditorArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
