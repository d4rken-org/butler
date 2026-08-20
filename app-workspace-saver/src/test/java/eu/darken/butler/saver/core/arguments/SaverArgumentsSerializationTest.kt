package eu.darken.butler.workspace.contracts.saver

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SaverArgumentsSerializationTest : BaseTest() {

    private val json = SerializationIOModule().json()

    @Test
    fun `serialize Default with all fields`() {
        val args = SaverArguments.Default(
            sourceUris = listOf(
                "content://media/external/images/1234",
                "content://media/external/images/5678",
            ),
            callerPackage = "com.example.app".toPkgId(),
            destinationPath = LocalPath.build("/sdcard/Download"),
        )
        val serialized = json.encodeToJsonElement<SaverArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "default",
                "sourceUris": [
                    "content://media/external/images/1234",
                    "content://media/external/images/5678"
                ],
                "callerPackage": {"name":"com.example.app"},
                "destinationPath": {
                    "type": "LOCAL",
                    "file": "/sdcard/Download"
                },
                "reportSavedPaths": false
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with minimal fields`() {
        val args = SaverArguments.Default(
            sourceUris = listOf("content://provider/file"),
            callerPackage = null,
        )
        val serialized = json.encodeToJsonElement<SaverArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "default",
                "sourceUris": ["content://provider/file"],
                "reportSavedPaths": false
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with all fields`() {
        val jsonString = """
            {
                "type": "default",
                "sourceUris": [
                    "content://media/external/video/5678",
                    "content://media/external/video/9999"
                ],
                "callerPackage": {"name":"com.another.app"},
                "destinationPath": {
                    "type": "LOCAL",
                    "file": "/storage/emulated/0/Movies"
                }
            }
        """

        val args = json.decodeFromString<SaverArguments>(jsonString)

        args shouldBe SaverArguments.Default(
            sourceUris = listOf(
                "content://media/external/video/5678",
                "content://media/external/video/9999",
            ),
            callerPackage = "com.another.app".toPkgId(),
            destinationPath = LocalPath.build("/storage/emulated/0/Movies"),
        )
    }

    @Test
    fun `deserialize Default with minimal fields`() {
        val jsonString = """
            {
                "type": "default",
                "sourceUris": ["content://downloads/123"]
            }
        """

        val args = json.decodeFromString<SaverArguments>(jsonString)

        args shouldBe SaverArguments.Default(
            sourceUris = listOf("content://downloads/123"),
            callerPackage = null,
        )
    }

    @Test
    fun `roundtrip Default with all fields`() {
        val original = SaverArguments.Default(
            sourceUris = listOf(
                "content://com.google.photos/shared/image123",
                "content://com.google.photos/shared/image456",
            ),
            callerPackage = "com.google.android.apps.photos".toPkgId(),
            destinationPath = LocalPath.build("/sdcard/Pictures"),
        )

        val serialized = json.encodeToJsonElement<SaverArguments>(original)
        val deserialized = json.decodeFromString<SaverArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Default with minimal fields`() {
        val original = SaverArguments.Default(
            sourceUris = listOf("content://media/external/downloads/999"),
            callerPackage = null,
        )

        val serialized = json.encodeToJsonElement<SaverArguments>(original)
        val deserialized = json.decodeFromString<SaverArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `callerWorkspaceId is transient and dropped from serialization`() {
        val args = SaverArguments.Default(
            sourceUris = listOf("content://provider/file"),
            callerWorkspaceId = Workspace.Id(),
        )
        val serialized = json.encodeToJsonElement<SaverArguments>(args)

        // Session-transient: the caller relationship must not appear in persisted JSON...
        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "default",
                "sourceUris": ["content://provider/file"],
                "reportSavedPaths": false
            }
        """.toComparableJson()

        // ...and deserializes back as a null-caller (normal-tab) Saver.
        json.decodeFromString<SaverArguments>(serialized.toString()) shouldBe SaverArguments.Default(
            sourceUris = listOf("content://provider/file"),
            callerPackage = null,
        )
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : SaverWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: SaverArguments): SaverWorkspace = error("unused")
        }
        val original = SaverArguments.Default(
            sourceUris = listOf("content://media/external/images/1234"),
            callerPackage = "com.example.app".toPkgId(),
            destinationPath = LocalPath.build("/sdcard/Download"),
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<SaverArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
