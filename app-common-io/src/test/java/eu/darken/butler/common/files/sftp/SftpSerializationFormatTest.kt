package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pins the stored shape of SFTP paths.
 *
 * A round trip cannot do this: encoding and decoding with the same code agrees with itself whatever
 * the type discriminator and the field names happen to be. These strings end up in user data, in
 * DataStore values and in the workspace session database's `arguments` column, so renaming `SFTP`,
 * `SFTP_LOOKUP` or any field below makes stored tabs and paths undecodable on upgrade. If a change
 * here is intended it needs a migration, not a new expected string.
 */
class SftpSerializationFormatTest : BaseTest() {

    private val json = SerializationIOModule().json()

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val path = SftpPath(locationId, listOf("photos", "a\\b .jpg"))

    private val storedPath =
        """{"type":"SFTP","locationId":"11111111-2222-3333-4444-555555555555","segments":["photos","a\\b .jpg"]}"""

    private val lookup = SftpPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.parse("2023-11-14T22:13:20Z"),
    )

    private val storedLookup = """{"type":"SFTP_LOOKUP","lookedUp":{"locationId":""" +
        """"11111111-2222-3333-4444-555555555555","segments":["photos","a\\b .jpg"]},""" +
        """"fileType":"FILE","size":1024,"modifiedAt":"2023-11-14T22:13:20Z"}"""

    private val link = SftpPathLookup(
        lookedUp = SftpPath(locationId, listOf("latest")),
        fileType = FileType.SYMBOLIC_LINK,
        size = 6L,
        modifiedAt = Instant.parse("2023-11-14T22:13:20Z"),
        target = SftpPath(locationId, listOf("photos")),
        ownership = Ownership(1000L, 100L),
        permissions = Permissions(0x1FF),
        linkTarget = "photos",
    )

    private val storedLink = """{"type":"SFTP_LOOKUP","lookedUp":{"locationId":""" +
        """"11111111-2222-3333-4444-555555555555","segments":["latest"]},""" +
        """"fileType":"SYMBOLIC_LINK","size":6,"modifiedAt":"2023-11-14T22:13:20Z",""" +
        """"target":{"locationId":"11111111-2222-3333-4444-555555555555","segments":["photos"]},""" +
        """"ownership":{"userId":1000,"groupId":100},"permissions":{"mode":511},"linkTarget":"photos"}"""

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
            """{"type":"SFTP","locationId":"11111111-2222-3333-4444-555555555555","segments":[]}"""
        json.encodeToString(PolymorphicSerializer(APath::class), SftpPath.root(locationId)) shouldBe expected
    }

    @Test
    fun `a lookup encodes to its stored shape`() {
        json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup) shouldBe storedLookup
    }

    @Test
    fun `a stored lookup still decodes`() {
        json.decodeFromString(PolymorphicSerializer(APathLookup::class), storedLookup) shouldBe lookup
    }

    @Test
    fun `a symbolic link lookup keeps its target and attributes`() {
        json.encodeToString(PolymorphicSerializer(APathLookup::class), link) shouldBe storedLink
        json.decodeFromString(PolymorphicSerializer(APathLookup::class), storedLink) shouldBe link
    }
}
