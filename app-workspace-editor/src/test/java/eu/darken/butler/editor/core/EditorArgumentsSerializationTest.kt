package eu.darken.butler.editor.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.editor.core.arguments.EditorArguments
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
            goToLine = 42,
        )
        val serialized = json.encodeToJsonElement<EditorArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filePath": {
                    "type": "LOCAL",
                    "file": "/sdcard/test.txt"
                },
                "goToLine": 42
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
            goToLine = 100,
        )

        val serialized = json.encodeToJsonElement<EditorArguments>(original)
        val deserialized = json.decodeFromString<EditorArguments>(serialized.toString())

        deserialized shouldBe original
    }
}
