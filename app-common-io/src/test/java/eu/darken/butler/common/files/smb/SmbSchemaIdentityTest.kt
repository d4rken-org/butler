package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialDatabase
import eu.darken.butler.common.files.smb.location.db.SmbLocationDatabase
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

/**
 * Covers both SMB databases, the network locations and the credentials they are paired with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbSchemaIdentityTest : BaseTest() {

    @Test
    fun `the network location schema versions are unchanged`() {
        roomSchemaIdentityHashes(SmbLocationDatabase::class.java) shouldBe mapOf(
            1 to "eb27303b4a3a1e72d78484ceddc62669",
            2 to "1ad05dcbfce071dad7fd762b538468fa",
        )
    }

    @Test
    fun `the credential schema versions are unchanged`() {
        roomSchemaIdentityHashes(SmbCredentialDatabase::class.java) shouldBe mapOf(
            1 to "ca94bee1fa1b7ad4e437aa8ee2738863",
        )
    }
}
