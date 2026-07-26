package eu.darken.butler.workspace.core.session.db

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
class WorkspaceSessionDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WorkspaceSessionDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(WorkspaceSessionDatabase::class.java, WorkspaceSessionDatabase.MIGRATIONS)
    }

    /**
     * [validateAllMigrations] migrates EMPTY databases: it proves the migration runs, not that user
     * data survives it. This walks a populated v1 database to v2 and checks the rows are intact.
     */
    @Test
    fun `migrating 1 to 2 preserves existing rows and defaults customTitle to null`() {
        val dbName = "migration-data-test"
        val sessionId = "default"
        val workspaceId = "8a2b6c1e-0000-4000-8000-000000000001"

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO workspace_sessions (sessionId, version, label, createdAt, updatedAt, uiState) " +
                    "VALUES ('$sessionId', 1, 'Default Session', 1000, 2000, '{}')"
            )
            execSQL(
                "INSERT INTO workspace_instances " +
                    "(workspaceId, sessionId, type, orderIndex, createdAt, lastModified, arguments) " +
                    "VALUES ('$workspaceId', '$sessionId', 'EXPLORER', 3, 1000, 2000, '{\"tag\":\"a\"}')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, *WorkspaceSessionDatabase.MIGRATIONS)

        migrated.query("SELECT * FROM workspace_instances").use { cursor ->
            cursor.count shouldBe 1
            cursor.moveToFirst() shouldBe true
            cursor.getString(cursor.getColumnIndexOrThrow("workspaceId")) shouldBe workspaceId
            cursor.getString(cursor.getColumnIndexOrThrow("sessionId")) shouldBe sessionId
            cursor.getString(cursor.getColumnIndexOrThrow("type")) shouldBe "EXPLORER"
            cursor.getInt(cursor.getColumnIndexOrThrow("orderIndex")) shouldBe 3
            cursor.getString(cursor.getColumnIndexOrThrow("arguments")) shouldBe "{\"tag\":\"a\"}"
            cursor.isNull(cursor.getColumnIndexOrThrow("customTitle")) shouldBe true
        }

        migrated.query("SELECT sessionId FROM workspace_sessions").use { cursor ->
            cursor.count shouldBe 1
            cursor.moveToFirst() shouldBe true
            cursor.getString(0) shouldBe sessionId
        }

        migrated.close()
    }
}
