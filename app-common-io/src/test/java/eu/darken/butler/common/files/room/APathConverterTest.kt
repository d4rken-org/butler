package eu.darken.butler.common.files.room

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class APathConverterTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val converter = APathConverter(json)

    @Test
    fun `LocalPath - roundtrip conversion`() {
        // Given
        val originalPath = LocalPath.build("/storage/emulated/0/test.txt")

        // When - convert to string and back
        val jsonString = converter.fromAPath(originalPath)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "LOCAL",
              "file" : "/storage/emulated/0/test.txt"
            }
        """.toComparableJson()
        val restored = converter.toAPath(jsonString)

        // Then
        restored shouldBe originalPath
        restored::class shouldBe LocalPath::class
    }

    @Test
    fun `SAFPath - roundtrip conversion`() {
        // Given
        val originalPath = SAFPath(
            treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            segments = listOf("folder", "test.txt"),
        )

        // When - convert to string and back
        val jsonString = converter.fromAPath(originalPath)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "SAF",
              "treeRoot" : "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
              "segments" : [ "folder", "test.txt" ]
            }
        """.toComparableJson()
        val restored = converter.toAPath(jsonString)

        // Then
        restored shouldBe originalPath
        restored::class shouldBe SAFPath::class
    }

}
