package eu.darken.butler.common.files

import android.net.Uri
import eu.darken.butler.common.files.saf.SAFDocFile
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
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

        val jsonString = json.encodeToString(original as APath)
        jsonString.toComparableJson() shouldBe """
            {
                "treeRoot": "$testUri",
                "segments": ["seg3","seg2","seg1"],
                "type":"SAF"
            }
        """.toComparableJson()

        json.decodeFromString<APath>(jsonString) shouldBe original
    }

    @Test
    fun `test polymorph list serialization`() {
        val original = listOf(
            SAFPath.build(testUri, "seg3", "seg2", "seg1"),
            SAFPath.build(testUri, "seg4", "seg5", "seg6"),
        )

        val jsonString = json.encodeToString(ListSerializer(APath.serializer()), original)

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

        json.decodeFromString(ListSerializer(APath.serializer()), jsonString) shouldBe original
    }

    @Test
    fun `test must be tree uri`() {
        shouldThrow<IllegalArgumentException> {
            SAFPath.Companion.build(Uri.parse("abc"))
        }
    }

    @Test
    fun `force typing`() {
        val original = RawPath.build("test", "file")

        shouldThrow<SerializationException> {
            val jsonString = json.encodeToString(RawPath.serializer(), original)
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
            docFile = mockk<SAFDocFile>().apply {
//                fileType = FileType.FILE,
//                size = 16,
//                modifiedAt = Instant.EPOCH,
//                ownership = null,
//                permissions = null,
//                target = null,
            }
        )
        val lookup1b = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "seg2"),
            docFile = mockk<SAFDocFile>().apply {
//                fileType = FileType.FILE,
//                size = 8,
//                modifiedAt = Instant.ofEpochMilli(123),
//                ownership = Ownership(1, 1),
//                permissions = Permissions(444),
//                target = null,
            }
        )
        val lookup1c = SAFPathLookup(
            SAFPath.build(testUri, "seg1", "seg2"),
            docFile = mockk<SAFDocFile>().apply {
//                fileType = FileType.DIRECTORY,
//                size = 16,
//                modifiedAt = Instant.EPOCH,
//                ownership = null,
//                permissions = null,
//                target = null,
            }
        )
        val lookup2 = SAFPathLookup(
            lookedUp = SAFPath.build(testUri, "seg1", "test"),
            docFile = mockk<SAFDocFile>().apply {
//                fileType = FileType.FILE,
//                size = 16,
//                modifiedAt = Instant.EPOCH,
//                ownership = null,
//                permissions = null,
//                target = null,
            }
        )
        lookup1a shouldNotBe lookup1b
        lookup1a shouldNotBe lookup1c
        lookup1a shouldNotBe lookup2
    }

    @Test
    fun `user readable path mapping`() {
        SAFPath.Companion.build(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Asafstor"),
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "/storage/emulated/0/seg1/seg2"
        SAFPath.Companion.build(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary"),
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "/storage/emulated/0/seg1/seg2"
        SAFPath.Companion.build(
            Uri.parse("content://com.android.externalstorage.documents/tree/3135-3132%3Asafstor"),
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "/storage/3135-3132/seg1/seg2"
        SAFPath.Companion.build(
            Uri.parse("content://com.android.externalstorage.documents/tree/3135-3132"),
            "seg1",
            "seg2",
        ).userReadablePath.get(mockk()) shouldBe "/storage/3135-3132/seg1/seg2"
    }
}