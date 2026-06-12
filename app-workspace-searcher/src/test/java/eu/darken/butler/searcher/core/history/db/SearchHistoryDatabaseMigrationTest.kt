package eu.darken.butler.searcher.core.history.db

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
class SearchHistoryDatabaseMigrationTest : BaseTest() {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SearchHistoryDatabase::class.java,
    )

    @Test
    fun `every schema version migrates to the latest version`() {
        helper.validateAllMigrations(SearchHistoryDatabase::class.java, SearchHistoryDatabase.MIGRATIONS)
    }
}
