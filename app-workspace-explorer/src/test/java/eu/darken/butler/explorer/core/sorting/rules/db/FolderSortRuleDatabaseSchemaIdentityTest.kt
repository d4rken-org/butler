package eu.darken.butler.explorer.core.sorting.rules.db

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderSortRuleDatabaseSchemaIdentityTest : BaseTest() {

    @Test
    fun `the schema versions are unchanged`() {
        roomSchemaIdentityHashes(FolderSortRuleDatabase::class.java) shouldBe mapOf(
            1 to "50849ef2507af8106097eabfd49894d9",
        )
    }
}
