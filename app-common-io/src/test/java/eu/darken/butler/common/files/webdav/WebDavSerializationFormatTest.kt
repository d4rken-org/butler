package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pins the stored shape of WebDAV paths.
 *
 * A round trip cannot do this: encoding and decoding with the same code agrees with itself whatever
 * the type discriminator and the field names happen to be. These strings end up in user data, in
 * DataStore values and in the workspace session database's `arguments` column, so renaming `WEBDAV`,
 * `WEBDAV_LOOKUP` or any field below makes stored tabs and paths undecodable on upgrade. If a change
 * here is intended it needs a migration, not a new expected string.
 */
class WebDavSerializationFormatTest : BaseTest() {

    private val json = SerializationIOModule().json()

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val path = WebDavPath(locationId, listOf("photos", "a\\b 100%.jpg"))

    private val storedPath =
        """{"type":"WEBDAV","locationId":"11111111-2222-3333-4444-555555555555","segments":["photos","a\\b 100%.jpg"]}"""

    private val lookup = WebDavPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.parse("2023-11-14T22:13:20Z"),
    )

    private val storedLookup = """{"type":"WEBDAV_LOOKUP","lookedUp":{"locationId":""" +
        """"11111111-2222-3333-4444-555555555555","segments":["photos","a\\b 100%.jpg"]},""" +
        """"fileType":"FILE","size":1024,"modifiedAt":"2023-11-14T22:13:20Z"}"""

    private val detailed = WebDavPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.parse("2023-11-14T22:13:20Z"),
        createdAt = Instant.parse("2023-11-01T08:00:00Z"),
        etag = "\"5f1c-64a2b3\"",
        contentType = "image/jpeg",
    )

    private val storedDetailed = """{"type":"WEBDAV_LOOKUP","lookedUp":{"locationId":""" +
        """"11111111-2222-3333-4444-555555555555","segments":["photos","a\\b 100%.jpg"]},""" +
        """"fileType":"FILE","size":1024,"modifiedAt":"2023-11-14T22:13:20Z",""" +
        """"createdAt":"2023-11-01T08:00:00Z","etag":"\"5f1c-64a2b3\"","contentType":"image/jpeg"}"""

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
            """{"type":"WEBDAV","locationId":"11111111-2222-3333-4444-555555555555","segments":[]}"""
        json.encodeToString(PolymorphicSerializer(APath::class), WebDavPath.root(locationId)) shouldBe expected
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
    fun `a lookup keeps its creation time, etag and content type`() {
        json.encodeToString(PolymorphicSerializer(APathLookup::class), detailed) shouldBe storedDetailed
        json.decodeFromString(PolymorphicSerializer(APathLookup::class), storedDetailed) shouldBe detailed
    }

    @Test
    fun `a lookup has no symbolic link target`() {
        detailed.target shouldBe null
    }
}
