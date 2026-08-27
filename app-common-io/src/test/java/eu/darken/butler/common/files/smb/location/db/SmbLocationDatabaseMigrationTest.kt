package eu.darken.butler.common.files.smb.location.db

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.validateAllMigrations

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbLocationDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmbLocationDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(SmbLocationDatabase::class.java, SmbLocationDatabase.MIGRATIONS)
    }

    /**
     * The shared helper above walks empty databases, so it would still pass if a migration dropped
     * every row. This one carries a stored location across.
     */
    @Test
    fun `a stored location survives the migration to version 2`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO smb_locations (locationId, label, host, port, share, basePath, domain, " +
                    "username, authType, rememberCredential, credentialVersion, createdAt, updatedAt) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Home NAS', 'nas.local', 445, " +
                    "'media', 'movies', 'WORKGROUP', 'darken', 'PASSWORD', 1, 3, 1000, 2000)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 2, true, *SmbLocationDatabase.MIGRATIONS).use { db ->
            db.query("SELECT * FROM smb_locations").use { cursor ->
                cursor.count shouldBe 1
                cursor.moveToFirst() shouldBe true
                cursor.string("locationId") shouldBe "11111111-1111-1111-1111-111111111111"
                cursor.string("label") shouldBe "Home NAS"
                cursor.string("host") shouldBe "nas.local"
                cursor.long("port") shouldBe 445L
                cursor.string("share") shouldBe "media"
                cursor.string("basePath") shouldBe "movies"
                cursor.string("domain") shouldBe "WORKGROUP"
                cursor.string("username") shouldBe "darken"
                cursor.string("authType") shouldBe "PASSWORD"
                cursor.long("rememberCredential") shouldBe 1L
                cursor.long("credentialVersion") shouldBe 3L
                cursor.long("createdAt") shouldBe 1000L
                cursor.long("updatedAt") shouldBe 2000L
                cursor.isNull(cursor.getColumnIndexOrThrow("lastSeenAt")) shouldBe true
            }
        }
    }

    private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    companion object {
        private const val DB_NAME = "smb-location-migration-test"
    }
}
