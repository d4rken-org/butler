package eu.darken.butler.common.files.serialization

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class APathLookupSerializationTest : BaseTest() {

    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    @Test
    fun `polymorphic deserialization restores correct type`() {
        val localLookup = LocalPathLookup(
            lookedUp = LocalPath.build("/local/path"),
            fileType = FileType.FILE,
            size = 100L,
            modifiedAt = null,
        )

        val safLookup = SAFPathLookup(
            lookedUp = SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                segments = listOf("saf", "path"),
            ),
            fileType = FileType.DIRECTORY,
            size = 4096L,
            modifiedAt = null,
        )

        val localJson = json.encodeToString(PolymorphicSerializer(APathLookup::class), localLookup)
        val safJson = json.encodeToString(PolymorphicSerializer(APathLookup::class), safLookup)

        val restoredLocal = json.decodeFromString(PolymorphicSerializer(APathLookup::class), localJson)
        val restoredSaf = json.decodeFromString(PolymorphicSerializer(APathLookup::class), safJson)

        restoredLocal::class shouldBe LocalPathLookup::class
        restoredSaf::class shouldBe SAFPathLookup::class
    }

    @Test
    fun `list serialization preserves polymorphic types`() {
        val lookups = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build("/local/first"),
                fileType = FileType.FILE,
                size = 100L,
                modifiedAt = null,
            ),
            SAFPathLookup(
                lookedUp = SAFPath(
                    treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                    segments = listOf("saf", "path"),
                ),
                fileType = FileType.DIRECTORY,
                size = 4096L,
                modifiedAt = null,
            ),
            LocalPathLookup(
                lookedUp = LocalPath.build("/local/second"),
                fileType = FileType.DIRECTORY,
                size = 0L,
                modifiedAt = null,
            ),
        )

        val serializer = ListSerializer(PolymorphicSerializer(APathLookup::class))
        val jsonString = json.encodeToString(serializer, lookups)
        val restored = json.decodeFromString(serializer, jsonString)

        restored.size shouldBe 3
        restored[0]::class shouldBe LocalPathLookup::class
        restored[1]::class shouldBe SAFPathLookup::class
        restored[2]::class shouldBe LocalPathLookup::class
    }
}
