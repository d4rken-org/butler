package eu.darken.butler.common.files.smb.credentials.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.validateAllMigrations

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbCredentialDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmbCredentialDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(SmbCredentialDatabase::class.java, SmbCredentialDatabase.MIGRATIONS)
    }
}
