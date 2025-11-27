package eu.darken.butler.saver.core.arguments

import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SaverArgumentsSerializationTest : BaseTest() {

    private val json = Json(SerializationCommonModule().json()) {
        serializersModule = SerializationCommonModule().json().serializersModule
    }

    @Test
    fun `serialize Default with all fields`() {
        val args = SaverArguments.Default(
            sourceUri = "content://media/external/images/1234",
            mimeType = "image/jpeg",
            callerPackage = "com.example.app",
            destinationPath = "/sdcard/Download",
            customFilename = "my_photo.jpg",
        )
        val serialized = json.encodeToJsonElement<SaverArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "default",
                "sourceUri": "content://media/external/images/1234",
                "mimeType": "image/jpeg",
                "callerPackage": "com.example.app",
                "destinationPath": "/sdcard/Download",
                "customFilename": "my_photo.jpg"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with minimal fields`() {
        val args = SaverArguments.Default(
            sourceUri = "content://provider/file",
            mimeType = null,
            callerPackage = null,
        )
        val serialized = json.encodeToJsonElement<SaverArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "default",
                "sourceUri": "content://provider/file"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with all fields`() {
        val jsonString = """
            {
                "type": "default",
                "sourceUri": "content://media/external/video/5678",
                "mimeType": "video/mp4",
                "callerPackage": "com.another.app",
                "destinationPath": "/storage/emulated/0/Movies",
                "customFilename": "movie.mp4"
            }
        """

        val args = json.decodeFromString<SaverArguments>(jsonString)

        args shouldBe SaverArguments.Default(
            sourceUri = "content://media/external/video/5678",
            mimeType = "video/mp4",
            callerPackage = "com.another.app",
            destinationPath = "/storage/emulated/0/Movies",
            customFilename = "movie.mp4",
        )
    }

    @Test
    fun `deserialize Default with minimal fields`() {
        val jsonString = """
            {
                "type": "default",
                "sourceUri": "content://downloads/123"
            }
        """

        val args = json.decodeFromString<SaverArguments>(jsonString)

        args shouldBe SaverArguments.Default(
            sourceUri = "content://downloads/123",
            mimeType = null,
            callerPackage = null,
        )
    }

    @Test
    fun `roundtrip Default with all fields`() {
        val original = SaverArguments.Default(
            sourceUri = "content://com.google.photos/shared/image123",
            mimeType = "image/png",
            callerPackage = "com.google.android.apps.photos",
            destinationPath = "/sdcard/Pictures",
            customFilename = "screenshot.png",
        )

        val serialized = json.encodeToJsonElement<SaverArguments>(original)
        val deserialized = json.decodeFromString<SaverArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `roundtrip Default with minimal fields`() {
        val original = SaverArguments.Default(
            sourceUri = "content://media/external/downloads/999",
            mimeType = null,
            callerPackage = null,
        )

        val serialized = json.encodeToJsonElement<SaverArguments>(original)
        val deserialized = json.decodeFromString<SaverArguments>(serialized.toString())

        deserialized shouldBe original
    }
}
