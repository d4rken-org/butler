package eu.darken.butler.common.files.room

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import kotlin.time.Instant

class APathLookupConverterTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val converter = APathLookupConverter(json)

    @Test
    fun `LocalPathLookup - roundtrip conversion`() {
        // Given
        val now = Instant.fromEpochMilliseconds(1234567890000L)
        val originalLookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/test.txt"),
            fileType = FileType.FILE,
            size = 2048L,
            modifiedAt = now,
            target = null,
            error = null,
            ownership = Ownership(userId = 1000L, groupId = 1000L, userName = "user", groupName = "group"),
            permissions = Permissions(mode = 0b110_100_100), // 644 in octal
            createdAt = now,
        )

        // When
        val jsonString = converter.fromAPathLookup(originalLookup)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "LOCAL_LOOKUP",
              "lookedUp" : {
                "file" : "/storage/emulated/0/test.txt"
              },
              "fileType" : "FILE",
              "size" : 2048,
              "modifiedAt" : "2009-02-13T23:31:30Z",
              "ownership" : {
                "userId" : 1000,
                "groupId" : 1000,
                "userName" : "user",
                "groupName" : "group"
              },
              "permissions" : {
                "mode" : 420
              },
              "createdAt" : "2009-02-13T23:31:30Z"
            }
        """.toComparableJson()
        val restored = converter.toAPathLookup(jsonString)

        // Then
        restored shouldBe originalLookup
        (restored as LocalPathLookup).apply {
            this.lookedUp shouldBe originalLookup.lookedUp
            this.fileType shouldBe originalLookup.fileType
            this.size shouldBe originalLookup.size
            this.modifiedAt shouldBe originalLookup.modifiedAt
            this.ownership shouldBe originalLookup.ownership
            this.permissions shouldBe originalLookup.permissions
            this.createdAt shouldBe originalLookup.createdAt
        }
    }

    @Test
    fun `SAFPathLookup - roundtrip conversion`() {
        // Given
        val now = Instant.fromEpochMilliseconds(9876543210000L)
        val originalLookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                segments = listOf("folder", "document.pdf"),
            ),
            fileType = FileType.FILE,
            size = 4096L,
            modifiedAt = now,
            target = null,
            error = null,
            ownership = Ownership(userId = 2000L, groupId = 2000L),
            permissions = Permissions(mode = 0b111_101_101), // 755 in octal
            createdAt = now,
        )

        // When
        val jsonString = converter.fromAPathLookup(originalLookup)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "SAF_LOOKUP",
              "lookedUp" : {
                "treeRoot" : "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                "segments" : [ "folder", "document.pdf" ]
              },
              "fileType" : "FILE",
              "size" : 4096,
              "modifiedAt" : "2282-12-22T20:13:30Z",
              "ownership" : {
                "userId" : 2000,
                "groupId" : 2000
              },
              "permissions" : {
                "mode" : 493
              },
              "createdAt" : "2282-12-22T20:13:30Z"
            }
        """.toComparableJson()
        val restored = converter.toAPathLookup(jsonString)

        // Then
        restored shouldBe originalLookup
        (restored as SAFPathLookup).apply {
            this.lookedUp shouldBe originalLookup.lookedUp
            this.fileType shouldBe originalLookup.fileType
            this.size shouldBe originalLookup.size
            this.modifiedAt shouldBe originalLookup.modifiedAt
            this.ownership shouldBe originalLookup.ownership
            this.permissions shouldBe originalLookup.permissions
            this.createdAt shouldBe originalLookup.createdAt
        }
    }
}
