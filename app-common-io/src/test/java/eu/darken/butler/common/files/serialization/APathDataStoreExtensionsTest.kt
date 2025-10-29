package eu.darken.butler.common.files.serialization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

class APathDataStoreExtensionsTest : BaseTest() {

    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    private fun createDataStore(scope: TestScope, tempDir: File): DataStore<Preferences> {
        val testFile = File(
            tempDir,
            "${APathDataStoreExtensionsTest::class.java.simpleName}_${System.nanoTime()}.preferences_pb"
        )
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile },
        )
    }

    @Test
    fun `read and write LocalPath`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val testPath = LocalPath.build("/storage/emulated/0/Download")

        testStore.createAPathValue("test.path", null, json).apply {
            // Default value should be null
            flow.first() shouldBe null
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            // Update to test path
            update { testPath }

            // Should be able to read back the same path
            val stored = flow.first()
            stored shouldBe testPath
            (stored as? LocalPath)?.path shouldBe "/storage/emulated/0/Download"

            // Update to null
            update { null }

            // Should return to default
            flow.first() shouldBe null
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `read and write SAFPath`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val testPath = SAFPath(
            treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            segments = listOf("Documents", "test.txt")
        )

        testStore.createAPathValue("test.path", null, json).apply {
            flow.first() shouldBe null

            update { testPath }

            val stored = flow.first()
            stored shouldBe testPath
            (stored as? SAFPath)?.segments shouldBe listOf("Documents", "test.txt")
        }
    }

    @Test
    fun `use custom default value`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val defaultPath = LocalPath.build("/sdcard")

        testStore.createAPathValue("test.path", defaultPath, json).apply {
            // Should return default value when nothing is stored
            flow.first() shouldBe defaultPath
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            // Update to different path
            val newPath = LocalPath.build("/storage/emulated/0/Download")
            update { newPath }

            flow.first() shouldBe newPath

            // Update to null should return default
            update { null }

            flow.first() shouldBe defaultPath
        }
    }

    @Test
    fun `polymorphic serialization preserves type information`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val paths = listOf<APath<*>>(
            LocalPath.build("/storage/emulated/0"),
            SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3A",
                segments = listOf("test")
            )
        )

        testStore.createAPathValue("test.path", null, json).apply {
            paths.forEach { originalPath ->
                update { originalPath }
                val restored = flow.first()

                // Should restore exact type
                restored shouldBe originalPath
                restored!!::class shouldBe originalPath::class

                // Verify specific properties based on type
                when (originalPath) {
                    is LocalPath -> (restored as LocalPath).file.path shouldBe originalPath.file.path
                    is SAFPath -> {
                        (restored as SAFPath).treeRoot shouldBe originalPath.treeRoot
                        restored.segments shouldBe originalPath.segments
                    }
                }
            }
        }
    }

    @Test
    fun `multiple instances with different keys work independently`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val path1 = LocalPath.build("/path1")
        val path2 = LocalPath.build("/path2")

        val value1 = testStore.createAPathValue("test.path1", null, json)
        val value2 = testStore.createAPathValue("test.path2", null, json)

        value1.update { path1 }
        value2.update { path2 }

        value1.flow.first() shouldBe path1
        value2.flow.first() shouldBe path2

        // Updating one doesn't affect the other
        value1.update { null }
        value1.flow.first() shouldBe null
        value2.flow.first() shouldBe path2
    }
}
