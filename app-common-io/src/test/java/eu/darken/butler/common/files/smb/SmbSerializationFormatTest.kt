package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pins the stored shape of SMB paths.
 *
 * A round trip cannot do this: encoding and decoding with the same code agrees with itself whatever
 * the type discriminator and the field names happen to be. These strings are already in user data,
 * in DataStore values and in the workspace session database's `arguments` column, so renaming
 * `SMB`, `SMB_LOOKUP` or any field below makes stored tabs and paths undecodable on upgrade. If a
 * change here is intended it needs a migration, not a new expected string.
 */
class SmbSerializationFormatTest : BaseTest() {

    private val json = SerializationIOModule().json()

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val path = SmbPath(locationId, listOf("movies", "2024.mkv"))

    private val storedPath =
        """{"type":"SMB","locationId":"11111111-2222-3333-4444-555555555555","segments":["movies","2024.mkv"]}"""

    private val lookup = SmbPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.parse("2023-11-14T22:13:20Z"),
    )

    private val storedLookup = """{"type":"SMB_LOOKUP","lookedUp":{"locationId":""" +
        """"11111111-2222-3333-4444-555555555555","segments":["movies","2024.mkv"]},""" +
        """"fileType":"FILE","size":1024,"modifiedAt":"2023-11-14T22:13:20Z"}"""

    @Test
    fun `a path encodes to its stored shape`() {
        json.encodeToString(PolymorphicSerializer(APath::class), path) shouldBe storedPath
    }

    @Test
    fun `a stored path still decodes`() {
        json.decodeFromString(PolymorphicSerializer(APath::class), storedPath) shouldBe path
    }

    @Test
    fun `a location root encodes with empty segments`() {
        val expected =
            """{"type":"SMB","locationId":"11111111-2222-3333-4444-555555555555","segments":[]}"""
        json.encodeToString(PolymorphicSerializer(APath::class), SmbPath.root(locationId)) shouldBe expected
    }

    @Test
    fun `a lookup encodes to its stored shape`() {
        json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup) shouldBe storedLookup
    }

    @Test
    fun `a stored lookup still decodes`() {
        json.decodeFromString(PolymorphicSerializer(APathLookup::class), storedLookup) shouldBe lookup
    }
}
