package eu.darken.butler.provider.documents.query

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.common.storage.StorageVolumeX
import eu.darken.butler.provider.documents.core.DocumentsProviderSettings
import eu.darken.butler.provider.documents.core.ProviderLocation
import eu.darken.butler.provider.documents.core.query.RootQueryHandler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RootQueryHandlerTest {

    private lateinit var context: Context
    private lateinit var settings: DocumentsProviderSettings
    private lateinit var storageManager2: StorageManager2
    private lateinit var handler: RootQueryHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        val isEnabledValue: DataStoreValue<Boolean> = mockk()
        every { isEnabledValue.flow } returns flowOf(true)

        settings = mockk {
            every { isEnabled } returns isEnabledValue
        }
        storageManager2 = mockk {
            every { storageVolumes } returns emptyList()
        }
        handler = RootQueryHandler(context, settings, storageManager2)
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
    fun `queryRoots with custom projection still returns all columns and valid data`() = runTest {
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
        )

        val cursor = handler.queryRoots(projection)

        // Custom projection is ignored to prevent MatrixCursor.RowBuilder.add crashes
        cursor.columnNames shouldBe arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        cursor.count shouldBe 1

        cursor.moveToFirst() shouldBe true
        val rootIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        cursor.getString(rootIdIndex) shouldBe ProviderLocation.Root.Butler.apiRootId
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

    @Test
    fun `queryRoots returns empty cursor when provider is disabled`() = runTest {
        // Create handler with disabled settings
        val disabledIsEnabledValue: DataStoreValue<Boolean> = mockk()
        every { disabledIsEnabledValue.flow } returns flowOf(false)

        val disabledSettings = mockk<DocumentsProviderSettings> {
            every { isEnabled } returns disabledIsEnabledValue
        }
        val disabledHandler = RootQueryHandler(context, disabledSettings, storageManager2)

        val cursor = disabledHandler.queryRoots(null)

        cursor.count shouldBe 0
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

    // ========== Step 5: Available bytes tests ==========

    @Test
    fun `queryRoots returns available bytes from primary volume`() = runTest {
        val mockDir = mockk<File> {
            every { usableSpace } returns 1024L * 1024L * 500L // 500MB
        }
        val primaryVolume = mockk<StorageVolumeX> {
            every { isPrimary } returns true
            every { isMounted } returns true
            every { directory } returns mockDir
        }
        every { storageManager2.storageVolumes } returns listOf(primaryVolume)

        val cursor = handler.queryRoots(null)
        cursor.moveToFirst() shouldBe true

        val bytesIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        cursor.getLong(bytesIndex) shouldBe 1024L * 1024L * 500L
    }

    @Test
    fun `queryRoots returns null available bytes when no primary volume`() = runTest {
        val nonPrimaryVolume = mockk<StorageVolumeX> {
            every { isPrimary } returns false
            every { isMounted } returns true
            every { directory } returns mockk()
        }
        every { storageManager2.storageVolumes } returns listOf(nonPrimaryVolume)

        val cursor = handler.queryRoots(null)
        cursor.moveToFirst() shouldBe true

        val bytesIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        cursor.isNull(bytesIndex) shouldBe true
    }

    @Test
    fun `queryRoots returns null available bytes when primary volume has no directory`() = runTest {
        val primaryVolume = mockk<StorageVolumeX> {
            every { isPrimary } returns true
            every { isMounted } returns true
            every { directory } returns null
        }
        every { storageManager2.storageVolumes } returns listOf(primaryVolume)

        val cursor = handler.queryRoots(null)
        cursor.moveToFirst() shouldBe true

        val bytesIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        cursor.isNull(bytesIndex) shouldBe true
    }
}
