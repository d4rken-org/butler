package eu.darken.butler.provider.documents.query

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.provider.documents.core.ProviderLocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RootQueryHandlerTest {

    private lateinit var context: Context
    private lateinit var handler: RootQueryHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        handler = RootQueryHandler(context)
    }

    @Test
    fun `queryRoots returns single Butler root`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.count shouldBe 1
    }

    @Test
    fun `queryRoots cursor has correct columns`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.columnNames shouldBe arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
    }

    @Test
    fun `queryRoots populates Butler root metadata correctly`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.moveToFirst() shouldBe true

        val rootIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        val iconIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ICON)
        val titleIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
        val summaryIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_SUMMARY)
        val flagsIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)

        // Verify values match ProviderLocation.Root.Butler
        cursor.getString(rootIdIndex) shouldBe ProviderLocation.Root.Butler.apiRootId
        cursor.getString(documentIdIndex) shouldBe ProviderLocation.Root.Butler.rootDocumentId
        cursor.getInt(iconIndex) shouldBe ProviderLocation.Root.Butler.icon
        cursor.getString(titleIndex) shouldNotBe null // CaString resolved
        cursor.getString(summaryIndex) shouldNotBe null // CaString resolved
        cursor.getInt(flagsIndex) shouldBe ProviderLocation.Root.Butler.flags
    }

    @Test
    fun `queryRoots with custom projection returns only requested columns`() = runTest {
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
        )

        val cursor = handler.queryRoots(projection)

        cursor.columnNames shouldBe projection
        cursor.count shouldBe 1
    }

    @Test
    fun `queryRoots Butler root has correct document ID`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.moveToFirst() shouldBe true

        val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        cursor.getString(documentIdIndex) shouldBe "butler"
    }

    @Test
    fun `queryRoots Butler root has correct apiRootId`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.moveToFirst() shouldBe true

        val rootIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        cursor.getString(rootIdIndex) shouldBe "butler"
    }

    @Test
    fun `queryRoots root document ID matches apiRootId`() = runTest {
        val cursor = handler.queryRoots(null)

        cursor.moveToFirst() shouldBe true

        val rootIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)

        cursor.getString(rootIdIndex) shouldBe cursor.getString(documentIdIndex)
    }
}
