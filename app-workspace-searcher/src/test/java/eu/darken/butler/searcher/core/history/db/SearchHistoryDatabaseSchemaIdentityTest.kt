package eu.darken.butler.searcher.core.history.db

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryDatabaseSchemaIdentityTest : BaseTest() {

    @Test
    fun `the schema versions are unchanged`() {
        roomSchemaIdentityHashes(SearchHistoryDatabase::class.java) shouldBe mapOf(
            1 to "ce6604e50860f36304273a7c05e9bcc0",
        )
    }
}
