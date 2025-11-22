package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import kotlin.time.Clock

class LocalPathLookupTest : BaseTest() {

    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    @Test
    fun `direct serialization without polymorphism`() {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/test/path"),
            fileType = FileType.FILE,
            size = 2048L,
            modifiedAt = null,
            ownership = Ownership(userId = 1000, groupId = 1000),
            permissions = Permissions(mode = 0b110100100),
        )

        val jsonString = json.encodeToString(lookup)
        val restored = json.decodeFromString<LocalPathLookup>(jsonString)

        restored shouldBe lookup
        jsonString.toComparableJson() shouldBe """
            {
              "lookedUp" : {
                "file" : "/test/path"
              },
              "fileType" : "FILE",
              "size" : 2048,
              "ownership" : {
                "userId" : 1000,
                "groupId" : 1000
              },
              "permissions" : {
                "mode" : 420
              }
            }
        """.toComparableJson()
    }

    @Test
    fun `LocalPathLookup serialization round trip`() {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/test.txt"),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = Clock.System.now(),
            ownership = Ownership(userId = 1000, groupId = 1000),
            permissions = Permissions(mode = 0b110100100),
            createdAt = Clock.System.now(),
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        val restored = json.decodeFromString(PolymorphicSerializer(APathLookup::class), jsonString)

        restored shouldBe lookup
        (restored as LocalPathLookup).lookedUp.path shouldBe "/storage/emulated/0/Download/test.txt"
        restored.fileType shouldBe FileType.FILE
        restored.size shouldBe 1024L
        restored.ownership shouldBe lookup.ownership
        restored.permissions shouldBe lookup.permissions
    }

    @Test
    fun `LocalPathLookup JSON format is stable`() {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/test/path"),
            fileType = FileType.DIRECTORY,
            size = 4096L,
            modifiedAt = null,
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        jsonString.toComparableJson() shouldBe """
            {
              "type" : "LOCAL_LOOKUP",
              "lookedUp" : {
                "file" : "/test/path"
              },
              "fileType" : "DIRECTORY",
              "size" : 4096
            }
        """.toComparableJson()
    }

    @Test
    fun `nullable fields are handled correctly`() {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/test"),
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
            target = null,
            error = null,
            ownership = null,
            permissions = null,
            createdAt = null,
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        val restored = json.decodeFromString(PolymorphicSerializer(APathLookup::class), jsonString)

        restored shouldBe lookup
        (restored as LocalPathLookup).size shouldBe null
        restored.modifiedAt shouldBe null
        restored.ownership shouldBe null
        restored.permissions shouldBe null
    }

    @Test
    fun `symbolic link lookup serializes correctly`() {
        val target = LocalPath.build("/actual/target")
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/symlink"),
            fileType = FileType.SYMBOLIC_LINK,
            size = 0L,
            modifiedAt = null,
            target = target,
        )

        val jsonString = json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)
        val restored = json.decodeFromString(PolymorphicSerializer(APathLookup::class), jsonString) as LocalPathLookup

        restored.fileType shouldBe FileType.SYMBOLIC_LINK
        restored.target shouldNotBe null
        restored.target?.path shouldBe "/actual/target"
    }
}
