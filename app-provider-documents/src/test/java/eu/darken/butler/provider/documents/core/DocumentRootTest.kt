package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.provider.documents.R
import eu.darken.butler.provider.documents.core.DocumentIdCodec.Companion.ROOT_DOCUMENT_ID
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DocumentRootTest : BaseTest() {

    @Test
    fun `Butler root has correct apiRootId`() {
        DocumentRoot.Butler.apiRootId shouldBe "butler"
    }

    @Test
    fun `Butler root has correct rootDocumentId`() {
        DocumentRoot.Butler.rootDocumentId shouldBe ROOT_DOCUMENT_ID
    }

    @Test
    fun `Butler root rootDocumentId matches apiRootId`() {
        DocumentRoot.Butler.rootDocumentId shouldBe DocumentRoot.Butler.apiRootId
    }

    @Test
    fun `Butler root has FLAG_SUPPORTS_IS_CHILD flag set`() {
        val flags = DocumentRoot.Butler.flags
        (flags and FLAG_SUPPORTS_IS_CHILD) shouldBe FLAG_SUPPORTS_IS_CHILD
    }

    @Test
    fun `Butler root has FLAG_LOCAL_ONLY flag set`() {
        val flags = DocumentRoot.Butler.flags
        (flags and FLAG_LOCAL_ONLY) shouldBe FLAG_LOCAL_ONLY
    }

    @Test
    fun `Butler root has valid icon resource ID`() {
        DocumentRoot.Butler.icon shouldBe android.R.drawable.ic_menu_manage
    }

    @Test
    fun `Butler root has title from string resource`() {
        val title = DocumentRoot.Butler.title
        title.shouldBeInstanceOf<CaString>()
        title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root has summary from string resource`() {
        val summary = DocumentRoot.Butler.summary
        summary shouldNotBe null
        summary.shouldBeInstanceOf<CaString>()
        summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Butler root is a DocumentRoot instance`() {
        val root: DocumentRoot = DocumentRoot.Butler
        root.shouldBeInstanceOf<DocumentRoot>()
    }

    @Test
    fun `sealed interface has only Butler implementation in Phase 1`() {
        // This test documents that Phase 1 has only one root
        // If future phases add more roots, this test will need updating
        val root: DocumentRoot = DocumentRoot.Butler
        when (root) {
            DocumentRoot.Butler -> {
                // Expected - only implementation in Phase 1
            }
            // No else needed - sealed interface exhaustiveness
        }
    }

    @Test
    fun `Butler root properties are stable and unchanging`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val root = DocumentRoot.Butler

        root.apiRootId shouldBe "butler"
        root.rootDocumentId shouldBe "butler"
        root.icon shouldBe android.R.drawable.ic_menu_manage
        root.flags shouldBe (FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY)
    }
}
