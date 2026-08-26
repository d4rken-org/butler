package eu.darken.butler.common.files.saf.location.db

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.room.roomSchemaIdentityHashes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SAFLocationDatabaseSchemaIdentityTest : BaseTest() {

    @Test
    fun `the schema versions are unchanged`() {
        roomSchemaIdentityHashes(SAFLocationDatabase::class.java) shouldBe mapOf(
            1 to "c841715e2095b5393d29e18d1660f510",
        )
    }
}
