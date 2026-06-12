package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
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

class ExplorerArgumentsSerializationTest : BaseTest() {

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
        val args = ExplorerArguments.Default()
        val serialized = json.encodeToJsonElement<ExplorerArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "standard"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with startPath includes type discriminator`() {
        val args = ExplorerArguments.Default(
            startPath = LocalPath.build("/sdcard/Download"),
        )
        val serialized = json.encodeToJsonElement<ExplorerArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "standard",
                "startPath": {
                    "type": "LOCAL",
                    "file": "/sdcard/Download"
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "standard",
                "startPath": {
                    "type": "LOCAL",
                    "file": "/sdcard/DCIM"
                }
            }
        """

        val args = json.decodeFromString<ExplorerArguments>(jsonString)

        args shouldBe ExplorerArguments.Default(
            startPath = LocalPath.build("/sdcard/DCIM"),
        )
    }

    @Test
    fun `deserialize Default without startPath`() {
        val jsonString = """
            {
                "type": "standard"
            }
        """

        val args = json.decodeFromString<ExplorerArguments>(jsonString)

        args shouldBe ExplorerArguments.Default()
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = ExplorerArguments.Default(
            startPath = LocalPath.build("/data/local/tmp"),
        )

        val serialized = json.encodeToJsonElement<ExplorerArguments>(original)
        val deserialized = json.decodeFromString<ExplorerArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Default without startPath`() {
        val original = ExplorerArguments.Default()

        val serialized = json.encodeToJsonElement<ExplorerArguments>(original)
        val deserialized = json.decodeFromString<ExplorerArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : ExplorerWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: ExplorerArguments): ExplorerWorkspace = error("unused")
        }
        val original = ExplorerArguments.Default(
            startPath = LocalPath.build("/sdcard/Download"),
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<ExplorerArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
