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
    fun `Butler root has correct apiRootId`() {
        ProviderLocation.Root.Butler.apiRootId shouldBe "butler"
    }

    @Test
    fun `Butler root has correct rootDocumentId`() {
        ProviderLocation.Root.Butler.rootDocumentId shouldBe "butler"
    }

    @Test
    fun `Butler root rootDocumentId matches apiRootId`() {
        ProviderLocation.Root.Butler.rootDocumentId shouldBe ProviderLocation.Root.Butler.apiRootId
    }

    @Test
    fun `Butler root has FLAG_SUPPORTS_IS_CHILD flag set`() {
        val flags = ProviderLocation.Root.Butler.flags
        (flags and FLAG_SUPPORTS_IS_CHILD) shouldBe FLAG_SUPPORTS_IS_CHILD
    }

    @Test
    fun `Butler root has FLAG_LOCAL_ONLY flag set`() {
        val flags = ProviderLocation.Root.Butler.flags
        (flags and FLAG_LOCAL_ONLY) shouldBe FLAG_LOCAL_ONLY
    }

    @Test
    fun `Butler root has valid icon resource ID`() {
        ProviderLocation.Root.Butler.icon shouldBe R.mipmap.ic_launcher
    }

    @Test
    fun `Butler root has title from string resource`() {
        ProviderLocation.Root.Butler.title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root has summary from string resource`() {
        ProviderLocation.Root.Butler.summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root properties are stable and unchanging`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val root = ProviderLocation.Root.Butler

        root.apiRootId shouldBe "butler"
        root.rootDocumentId shouldBe "butler"
        root.icon shouldBe R.mipmap.ic_launcher
        root.flags shouldBe (FLAG_SUPPORTS_CREATE or FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY)
    }
}
