package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialDatabase
import eu.darken.butler.common.files.webdav.location.db.WebDavLocationDatabase
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

/**
 * Covers both WebDAV databases, the server locations and the credentials they are paired with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavSchemaIdentityTest : BaseTest() {

    @Test
    fun `the server location schema versions are unchanged`() {
        roomSchemaIdentityHashes(WebDavLocationDatabase::class.java) shouldBe mapOf(
            1 to "181e5cd1bdac4e80d462ce2959e2e368",
        )
    }

    @Test
    fun `the credential schema versions are unchanged`() {
        roomSchemaIdentityHashes(WebDavCredentialDatabase::class.java) shouldBe mapOf(
            1 to "c5d6731c09cdeb5380a27ad4464f82f1",
        )
    }
}
