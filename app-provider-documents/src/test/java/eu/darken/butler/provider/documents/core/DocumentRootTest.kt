package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Root.*
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.provider.documents.R
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DocumentRootTest : BaseTest() {

    @Test
    fun `Butler root has title from string resource`() {
        ProviderLocation.Root.Butler.title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root has summary from string resource`() {
        ProviderLocation.Root.Butler.summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root wire format is stable`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val root = ProviderLocation.Root.Butler

        root.apiRootId shouldBe "butler"
        root.rootDocumentId shouldBe "butler"
        root.icon shouldBe R.mipmap.ic_launcher
        root.flags shouldBe (FLAG_SUPPORTS_CREATE or FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY)
    }
}
