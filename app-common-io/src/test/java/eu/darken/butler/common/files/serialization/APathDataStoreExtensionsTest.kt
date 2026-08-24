package eu.darken.butler.common.files.serialization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
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
                    is eu.darken.butler.common.files.ArchivePath -> {
                        (restored as eu.darken.butler.common.files.ArchivePath).container shouldBe originalPath.container
                        restored.segments shouldBe originalPath.segments
                    }
                    is eu.darken.butler.common.files.SmbPath -> {
                        (restored as eu.darken.butler.common.files.SmbPath).locationId shouldBe originalPath.locationId
                        restored.segments shouldBe originalPath.segments
                    }
                }
            }
        }
    }

    @Test
    fun `list - default value is empty when nothing stored`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createAPathListValue("favorites.paths", emptyList(), json).apply {
            flow.first() shouldBe emptyList()
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `list - round trip mixed Local and SAF paths`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val mixed = listOf<APath<*>>(
            LocalPath.build("/storage/emulated/0/Download"),
            SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                segments = listOf("Documents")
            ),
            LocalPath.build("/storage/emulated/0/DCIM/Camera"),
        )

        testStore.createAPathListValue("favorites.paths", emptyList(), json).apply {
            update { mixed }

            val restored = flow.first()
            restored shouldHaveSize 3
            restored[0].shouldBeInstanceOf<LocalPath>()
            restored[1].shouldBeInstanceOf<SAFPath>()
            restored[2].shouldBeInstanceOf<LocalPath>()
            restored shouldContainExactly mixed
        }
    }

    @Test
    fun `list - empty list is persisted not treated as default`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val nonEmptyDefault = listOf<APath<*>>(LocalPath.build("/some/default"))
        testStore.createAPathListValue("favorites.paths", nonEmptyDefault, json).apply {
            // Initially no value stored → default returned
            flow.first() shouldBe nonEmptyDefault

            // User clears the list
            update { emptyList() }

            // Reader sees stored "[]", not null → returns empty, NOT the default
            flow.first() shouldBe emptyList()
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe "[]"
        }
    }

    @Test
    fun `list - corrupted JSON returns default, does not throw`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val key = "favorites.paths"
        // Pre-poison the prefs store with invalid JSON for this key
        testStore.edit { it[stringPreferencesKey(key)] = "{ this is not valid json [" }

        val fallback = listOf<APath<*>>(LocalPath.build("/fallback"))
        testStore.createAPathListValue(key, fallback, json).apply {
            // Should not crash; should fall back to the default
            flow.first() shouldBe fallback
        }
    }

    @Test
    fun `list - multiple keys remain independent`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val a = listOf<APath<*>>(LocalPath.build("/a"))
        val b = listOf<APath<*>>(LocalPath.build("/b1"), LocalPath.build("/b2"))

        val valueA = testStore.createAPathListValue("favs.a", emptyList(), json)
        val valueB = testStore.createAPathListValue("favs.b", emptyList(), json)

        valueA.update { a }
        valueB.update { b }

        valueA.flow.first() shouldBe a
        valueB.flow.first() shouldBe b

        valueA.update { emptyList() }
        valueA.flow.first() shouldBe emptyList()
        valueB.flow.first() shouldBe b
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
