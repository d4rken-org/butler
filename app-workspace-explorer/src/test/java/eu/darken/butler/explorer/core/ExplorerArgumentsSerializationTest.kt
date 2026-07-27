package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import kotlin.uuid.Uuid

class ExplorerArgumentsSerializationTest : BaseTest() {

    private val json = Json(SerializationCommonModule().json()) {
        serializersModule = SerializersModule {
            include(SerializationCommonModule().json().serializersModule)
            polymorphic(APath::class) {
                subclass(LocalPath::class)
                subclass(SAFPath::class)
            }
            contextual(WorkspaceIdSerializer)
            contextual(SelectionSerializer)
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
    fun `serialize Default with a start target`() {
        val args = ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH)
        val serialized = json.encodeToJsonElement<ExplorerArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "standard",
                "startTarget": "trash"
            }
        """.toComparableJson()
    }

    @Test
    fun `legacy arguments without a start target still deserialize`() {
        // Exactly what sessions saved before startTarget existed
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

        args shouldBe ExplorerArguments.Default(startPath = LocalPath.build("/sdcard/DCIM"))
        (args as ExplorerArguments.Default).startTarget shouldBe null
    }

    @Test
    fun `roundtrip Default with a start target`() {
        val original = ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME)

        val serialized = json.encodeToJsonElement<ExplorerArguments>(original)
        val deserialized = json.decodeFromString<ExplorerArguments>(serialized.toString())

        deserialized shouldBe original
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

    @Test
    fun `roundtrip Picker keeps the default pane-local presentation`() {
        val original = ExplorerArguments.Picker(
            startPath = LocalPath.build("/sdcard/Download"),
            callerWorkspaceId = Workspace.Id(),
        )

        val serialized = json.encodeToJsonElement<ExplorerArguments>(original)
        val deserialized = json.decodeFromString<ExplorerArguments>(serialized.toString())

        deserialized shouldBe original
        (deserialized as ExplorerArguments.Picker).modalPresentation shouldBe
            Workspace.ModalPresentationMode.PANE_LOCAL
    }

    @Test
    fun `roundtrip Picker keeps an explicit full-screen presentation`() {
        val original = ExplorerArguments.Picker(
            selection = PickerConfig.Selection.FileSingle,
            callerWorkspaceId = Workspace.Id(),
            modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
        )

        val serialized = json.encodeToJsonElement<ExplorerArguments>(original)
        val deserialized = json.decodeFromString<ExplorerArguments>(serialized.toString())

        deserialized shouldBe original
        (deserialized as ExplorerArguments.Picker).modalPresentation shouldBe
            Workspace.ModalPresentationMode.FULL_SCREEN
    }
}

/*
 * Pickers are sub-workspaces and therefore never persisted in a session, so the app registers no
 * serializers for their contextual fields. These two stand in for that, far enough to prove the
 * presentation mode survives a round trip.
 */

private object WorkspaceIdSerializer : KSerializer<Workspace.Id> {
    override val descriptor = PrimitiveSerialDescriptor("Workspace.Id", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Workspace.Id) = encoder.encodeString(value.longTag)
    override fun deserialize(decoder: Decoder) = Workspace.Id(Uuid.parse(decoder.decodeString()))
}

private object SelectionSerializer : KSerializer<PickerConfig.Selection> {
    override val descriptor = PrimitiveSerialDescriptor("PickerConfig.Selection", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PickerConfig.Selection) =
        encoder.encodeString(requireNotNull(value::class.simpleName))

    override fun deserialize(decoder: Decoder): PickerConfig.Selection = when (val name = decoder.decodeString()) {
        "DirectorySingle" -> PickerConfig.Selection.DirectorySingle
        "DirectoryMulti" -> PickerConfig.Selection.DirectoryMulti
        "FileSingle" -> PickerConfig.Selection.FileSingle
        "FileMulti" -> PickerConfig.Selection.FileMulti
        "MixedMulti" -> PickerConfig.Selection.MixedMulti
        else -> error("Selection not covered by this test serializer: $name")
    }
}
