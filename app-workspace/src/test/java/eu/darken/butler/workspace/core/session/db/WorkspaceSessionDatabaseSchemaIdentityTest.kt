package eu.darken.butler.workspace.core.session.db

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceSessionDatabaseSchemaIdentityTest : BaseTest() {

    @Test
    fun `the schema versions are unchanged`() {
        roomSchemaIdentityHashes(WorkspaceSessionDatabase::class.java) shouldBe mapOf(
            1 to "2ad11220d9ca13aae6114018d1ec6de7",
            2 to "fa86981254f536bf2b9c7e06eab7cc03",
        )
    }
}
