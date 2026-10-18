package eu.darken.butler.common.files.sftp.location.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.validateAllMigrations
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SftpLocationDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SftpLocationDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(SftpLocationDatabase::class.java, SftpLocationDatabase.MIGRATIONS)
    }

    /**
     * The shared helper above walks empty databases, so it would still pass if a migration dropped
     * every row. This one carries a stored key location across.
     */
    @Test
    fun `a stored location survives the migration to version 2 and keeps remembering its passphrase`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO sftp_locations (locationId, label, host, port, username, basePath, authType, " +
                    "rememberCredential, credentialVersion, hostKeyType, hostKeyBlob, hostKeyFingerprint, " +
                    "trustRevision, createdAt, updatedAt, lastSeenAt) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'NAS', 'nas.local', 2222, 'darken', '/srv', " +
                    "'PRIVATE_KEY', 1, 3, 'ssh-ed25519', X'0102', 'SHA256:abc', 2, 1000, 2000, 3000)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 2, true, *SftpLocationDatabase.MIGRATIONS).use { db ->
            db.query("SELECT * FROM sftp_locations").use { cursor ->
                cursor.count shouldBe 1
                cursor.moveToFirst() shouldBe true
                cursor.string("locationId") shouldBe "11111111-1111-1111-1111-111111111111"
                cursor.string("label") shouldBe "NAS"
                cursor.string("host") shouldBe "nas.local"
                cursor.long("port") shouldBe 2222L
                cursor.string("username") shouldBe "darken"
                cursor.string("basePath") shouldBe "/srv"
                cursor.string("authType") shouldBe "PRIVATE_KEY"
                cursor.long("rememberCredential") shouldBe 1L
                cursor.long("rememberPassphrase") shouldBe 1L
                cursor.long("credentialVersion") shouldBe 3L
                cursor.string("hostKeyType") shouldBe "ssh-ed25519"
                cursor.getBlob(cursor.getColumnIndexOrThrow("hostKeyBlob")).toList() shouldBe listOf<Byte>(1, 2)
                cursor.string("hostKeyFingerprint") shouldBe "SHA256:abc"
                cursor.long("trustRevision") shouldBe 2L
                cursor.long("createdAt") shouldBe 1000L
                cursor.long("updatedAt") shouldBe 2000L
                cursor.long("lastSeenAt") shouldBe 3000L
            }
        }

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SftpLocationDatabase::class.java,
            DB_NAME,
        ).addMigrations(*SftpLocationDatabase.MIGRATIONS).build()
        helper.closeWhenFinished(migrated)
        val row = runBlocking { migrated.sftpLocations().get(Uuid.parse("11111111-1111-1111-1111-111111111111")) }
        row!!.rememberPassphrase shouldBe true
    }

    private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    companion object {
        private const val DB_NAME = "sftp-location-migration-test"
    }
}
