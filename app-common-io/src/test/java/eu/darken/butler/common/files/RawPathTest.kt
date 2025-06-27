package eu.darken.butler.common.files

import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import testhelpers.json.toComparableJson
import java.io.File

class RawPathTest {
    private val json = SerializationIOModule().json()

    @Test
    fun `test polymorph serialization`() {
        val original = RawPath.build("test", "file")

        val jsonString = json.encodeToString(original as APath)
        jsonString.toComparableJson() shouldBe """
            {
                "path": "test/file",
                "type": "RAW"
            }
        """.toComparableJson()

        json.decodeFromString<APath>(jsonString) shouldBe original
    }

    @Test
    fun `test direct serialization`() {
        val original = RawPath.build("test", "file")

        val jsonString = json.encodeToString(RawPath.serializer(), original)
        jsonString.toComparableJson() shouldBe """
            {
                "path": "test/file"
            }
        """.toComparableJson()

        json.decodeFromString(RawPath.serializer(), jsonString) shouldBe original
    }

//    @Test
//    fun `test fixed type`() {
//        val file = RawPath.build("test", "file")
//        file.pathType shouldBe APath.PathType.RAW
//        shouldThrow<IllegalArgumentException> {
//            file.pathType = APath.PathType.LOCAL
//            Any()
//        }
//        file.pathType shouldBe APath.PathType.RAW
//    }

    @Test
    fun `force typing`() {
        val original = LocalPath.build(file = File("./testfile"))

        shouldThrow<SerializationException> {
            val jsonString = json.encodeToString(LocalPath.serializer(), original)
            json.decodeFromString(RawPath.serializer(), jsonString)
        }
    }
}