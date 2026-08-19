package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class OperationHistoryDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OperationHistoryDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(OperationHistoryDatabase::class.java, OperationHistoryDatabase.MIGRATIONS)
    }

    /**
     * [validateAllMigrations] migrates EMPTY databases: it proves the migration runs, not what it
     * does to existing rows. v1 rows conflate reported and intended paths under synthetic change
     * labels, so 1->2 deliberately drops them rather than guessing.
     */
    @Test
    fun `migrating 1 to 2 clears old history and adds the scope table`() {
        val dbName = "migration-data-test"

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO operation_history (id, kind, intent, originType, originWorkspaceId, " +
                    "title, description, summary, startedAt, completedAt, durationMs, outcome, " +
                    "errorMessage, errorClass, affectedPathsCount, partialErrorCount, pathsTruncated) " +
                    "VALUES ('op-1', 'COPY', NULL, 'EXPLORER', 'ws-1', 'Copy', 'desc', NULL, " +
                    "1000, 2000, 1000, 'COMPLETED', NULL, NULL, 2, 0, 0)"
            )
            execSQL(
                "INSERT INTO operation_history_paths " +
                    "(operationHistoryId, path, previousPath, change, sortIndex) " +
                    "VALUES ('op-1', '/sdcard/Download/photo.jpg', NULL, 'ADDED', 0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, *OperationHistoryDatabase.MIGRATIONS)

        migrated.countOf("operation_history") shouldBe 0
        migrated.countOf("operation_history_paths") shouldBe 0
        migrated.countOf("operation_history_scope") shouldBe 0

        migrated.execSQL(
            "INSERT INTO operation_history (id, kind, intent, originType, originWorkspaceId, " +
                "title, description, summary, startedAt, completedAt, durationMs, outcome, " +
                "errorMessage, errorClass, affectedPathsCount, partialErrorCount, pathsTruncated, primaryPath) " +
                "VALUES ('op-2', 'COPY', NULL, 'EXPLORER', 'ws-1', 'Copy', 'desc', NULL, " +
                "1000, 2000, 1000, 'COMPLETED', NULL, NULL, 0, 0, 0, '/sdcard/Backup/photo.jpg')"
        )
        migrated.execSQL(
            "INSERT INTO operation_history_scope (operationHistoryId, path, sortIndex) " +
                "VALUES ('op-2', '/sdcard/Backup', 0)"
        )
        migrated.countOf("operation_history_scope") shouldBe 1

        migrated.close()
    }

    private fun SupportSQLiteDatabase.countOf(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
