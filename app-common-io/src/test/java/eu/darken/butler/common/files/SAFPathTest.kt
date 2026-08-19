package eu.darken.butler.common.files

import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import kotlin.time.Instant

class SAFPathTest : BaseTest() {

    val testUri = "content://com.android.externalstorage.documents/tree/primary%3Asafstor"

    private val json = SerializationIOModule().json()

    @Test
    fun `test direct serialization`() {
        val original = SAFPath.build(testUri, "seg1", "seg2", "seg3")

        val jsonString = json.encodeToString(original)
        jsonString.toComparableJson() shouldBe """
            {
                "treeRoot": "$testUri",
                "segments": ["seg1","seg2","seg3"]
            }
        """.toComparableJson()

        json.decodeFromString<SAFPath>(jsonString) shouldBe original
    }

    @Test
    fun `test polymorph serialization`() {
        val original = SAFPath.build(testUri, "seg3", "seg2", "seg1")

        val jsonString = json.encodeToString<APath<SAFPath>>(original)
        jsonString.toComparableJson() shouldBe """
            {
                "treeRoot": "$testUri",
                "segments": ["seg3","seg2","seg1"],
                "type":"SAF"
            }
        """.toComparableJson()

        json.decodeFromString<APath<SAFPath>>(jsonString) shouldBe original
    }

    @Test
    fun `test polymorph list serialization`() {
        val original = listOf(
            SAFPath.build(testUri, "seg3", "seg2", "seg1"),
            SAFPath.build(testUri, "seg4", "seg5", "seg6"),
        )

        val jsonString = json.encodeToString(ListSerializer(serializer<APath<SAFPath>>()), original)

        jsonString.toComparableJson() shouldBe """
                [
                    {
                        "treeRoot": "$testUri",
                        "segments": ["seg3","seg2","seg1"],
                        "type":"SAF"
                    }, {
                        "treeRoot": "$testUri",
                        "segments": ["seg4","seg5","seg6"],
                        "type":"SAF"
                    }
                ]
        """.toComparableJson()

        json.decodeFromString(ListSerializer(serializer<APath<SAFPath>>()), jsonString) shouldBe original
    }

    @Test
    fun `pathUri separates repeated segments by position, not by value`() {
        // An earlier segment repeating the last one made a by-value "am I the last segment?" check
        // skip its separator, gluing two segments together. pathUri feeds permission matching.
        SAFPath.build(testUri, "files", "cache", "files").pathUri.toString() shouldBe
            "$testUri%3Afiles%2Fcache%2Ffiles"

        SAFPath.build(testUri, "Download", "foo", "Download").pathUri.toString() shouldBe
            "$testUri%3ADownload%2Ffoo%2FDownload"
    }

    @Test
    fun `test must be tree uri`() {
        shouldThrow<IllegalArgumentException> {
            SAFPath.build("abc")
        }
    }

    @Test
    fun `force typing`() {
        val original = LocalPath.build("test", "file")

        shouldThrow<SerializationException> {
            val jsonString = json.encodeToString(LocalPath.serializer(), original)
            json.decodeFromString(SAFPath.serializer(), jsonString)
        }
    }

    @Test
    fun `path comparison`() {
        val file1a = SAFPath.build(testUri, "seg1", "seg2")
        val file1b = SAFPath.build(testUri, "seg1", "seg2")
        val file2 = SAFPath.build(testUri, "seg1", "test")
        file1a shouldBe file1b
        file1a shouldNotBe file2
    }

    @Test
    fun `lookup comparison`() {
        val lookup1a = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "seg2"),
            fileType = FileType.FILE,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
        )
        val lookup1b = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "seg2"),
            fileType = FileType.FILE,
            size = 8,
            modifiedAt = Instant.fromEpochMilliseconds(123),
        )
        val lookup1c = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "seg2"),
            fileType = FileType.DIRECTORY,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
        )
        val lookup2 = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "test"),
            fileType = FileType.FILE,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
        )
        lookup1a shouldNotBe lookup1b
        lookup1a shouldNotBe lookup1c
        lookup1a shouldNotBe lookup2
    }

    @Test
    fun `user readable path mapping`() {
        SAFPath.build(
            "content://com.android.externalstorage.documents/tree/primary%3Asafstor",
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "[primary]/safstor/seg1/seg2"
        SAFPath.build(
            "content://com.android.externalstorage.documents/tree/primary",
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "[primary]/seg1/seg2"
        SAFPath.build(
            "content://com.android.externalstorage.documents/tree/3135-3132%3Asafstor",
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "[3135-3132]/safstor/seg1/seg2"
        SAFPath.build(
            "content://com.android.externalstorage.documents/tree/3135-3132",
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "[3135-3132]/seg1/seg2"
    }

    @Test
    fun `parent generation`() {
        SAFPath.build(testUri, "a", "b", "c").parent shouldBe SAFPath.build(testUri, "a", "b")
        SAFPath.build(testUri, "a").parent shouldBe SAFPath.build(testUri)
        SAFPath.build(testUri).parent shouldBe null
    }
}