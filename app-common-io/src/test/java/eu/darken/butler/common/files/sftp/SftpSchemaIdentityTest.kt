package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialDatabase
import eu.darken.butler.common.files.sftp.location.db.SftpLocationDatabase
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

/**
 * Covers both SFTP databases, the server locations and the credentials they are paired with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SftpSchemaIdentityTest : BaseTest() {

    @Test
    fun `the server location schema versions are unchanged`() {
        roomSchemaIdentityHashes(SftpLocationDatabase::class.java) shouldBe mapOf(
            1 to "c8e9c1e3405c2385983d9c30708b65e2",
        )
    }

    @Test
    fun `the credential schema versions are unchanged`() {
        roomSchemaIdentityHashes(SftpCredentialDatabase::class.java) shouldBe mapOf(
            1 to "1a701db79557def8da537891c862c107",
        )
    }
}
