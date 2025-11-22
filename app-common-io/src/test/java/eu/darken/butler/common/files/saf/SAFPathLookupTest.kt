package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import kotlin.time.Clock

class SAFPathLookupTest : BaseTest() {

    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    @Test
    fun `direct serialization without polymorphism`() {
        val lookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://example/tree/primary",
                segments = listOf("test.txt"),
            ),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        )

        val jsonString = json.encodeToString(lookup)
        val restored = json.decodeFromString<SAFPathLookup>(jsonString)

        restored shouldBe lookup
        jsonString.toComparableJson() shouldBe """
            {
              "lookedUp" : {
                "treeRoot" : "content://example/tree/primary",
                "segments" : [ "test.txt" ]
              },
              "fileType" : "FILE",
              "size" : 1024
            }
        """.toComparableJson()
    }

    @Test
    fun `SAFPathLookup serialization round trip`() {
        val lookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                segments = listOf("test.txt"),
            ),
            fileType = FileType.FILE,
            size = 2048L,
            modifiedAt = Clock.System.now(),
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        val restored = json.decodeFromString(PolymorphicSerializer(APathLookup::class), jsonString)

        restored shouldBe lookup
        (restored as SAFPathLookup).lookedUp.segments shouldBe listOf("test.txt")
        restored.fileType shouldBe FileType.FILE
    }

    @Test
    fun `SAFPathLookup JSON format is stable`() {
        val lookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://example/tree/primary",
                segments = listOf("folder", "file.txt"),
            ),
            fileType = FileType.FILE,
            size = 100L,
            modifiedAt = null,
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "SAF_LOOKUP",
              "lookedUp" : {
                "treeRoot" : "content://example/tree/primary",
                "segments" : [ "folder", "file.txt" ]
              },
              "fileType" : "FILE",
              "size" : 100
            }
        """.toComparableJson()
    }

    @Test
    fun `nullable fields are handled correctly`() {
        val lookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://example/tree/primary",
                segments = listOf("test.txt"),
            ),
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        val restored = json.decodeFromString(PolymorphicSerializer(APathLookup::class), jsonString)

        restored shouldBe lookup
        (restored as SAFPathLookup).size shouldBe null
        restored.modifiedAt shouldBe null
    }
}
