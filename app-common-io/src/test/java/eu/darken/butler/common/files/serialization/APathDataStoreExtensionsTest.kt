package eu.darken.butler.common.files.serialization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class APathDataStoreExtensionsTest : BaseTest() {

    private val testFiles = mutableListOf<File>()

    private fun createDataStore(scope: TestScope): DataStore<Preferences> {
        val testFile = File(
            IO_TEST_BASEDIR,
            "${APathDataStoreExtensionsTest::class.java.simpleName}_${System.nanoTime()}.preferences_pb"
        )
        testFile.delete()
        testFiles.add(testFile)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile },
        )
    }

    @AfterEach
    fun tearDown() {
        testFiles.forEach { it.delete() }
        testFiles.clear()
    }

    @Test
    fun `read and write LocalPath`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

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
    fun `read and write RawPath`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val testPath = RawPath("/data/data/com.example.app")

        testStore.createAPathValue("test.path", null, json).apply {
            flow.first() shouldBe null

            update { testPath }

            val stored = flow.first()
            stored shouldBe testPath
            (stored as? RawPath)?.path shouldBe "/data/data/com.example.app"
        }
    }

    @Test
    fun `read and write SAFPath`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

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
    fun `use custom default value`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

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
    fun `polymorphic serialization preserves type information`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val paths = listOf<APath<*>>(
            LocalPath.build("/storage/emulated/0"),
            RawPath("/data/local/tmp"),
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
                    is RawPath -> (restored as RawPath).path shouldBe originalPath.path
                    is SAFPath -> {
                        (restored as SAFPath).treeRoot shouldBe originalPath.treeRoot
                        restored.segments shouldBe originalPath.segments
                    }
                }
            }
        }
    }

    @Test
    fun `multiple instances with different keys work independently`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

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
