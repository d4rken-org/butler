package eu.darken.butler.workspace.core.operations.history.db

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationHistoryDatabaseSchemaIdentityTest : BaseTest() {

    @Test
    fun `the schema versions are unchanged`() {
        roomSchemaIdentityHashes(OperationHistoryDatabase::class.java) shouldBe mapOf(
            1 to "d954c3851505bf400b9cddb7fa6df8fe",
            2 to "5904c0e95136be0e50ce4c6ad0c75012",
        )
    }
}
