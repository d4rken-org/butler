package eu.darken.butler.common.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.OutputStream

class DataStoreValueTest : BaseTest() {

    private fun createDataStore(scope: TestScope, tempDir: File): DataStore<Preferences> {
        val testFile = File(
            tempDir,
            "${DataStoreValueTest::class.java.simpleName}_${System.nanoTime()}.preferences_pb"
        )
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile },
        )
    }

    @Test
    fun `collector started during a write receives the committed value`(@TempDir tempDir: File) = runTest {
        val key = intPreferencesKey("counter")
        val file = File(tempDir, "concurrent.preferences_pb")
        val writing = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val serializer = object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                if (t[key] == 1) {
                    // DataStore has advanced its version, but the final file is still unchanged.
                    writing.complete(Unit)
                    releaseWrite.await()
                }
                PreferencesFileSerializer.writeTo(t, output)
            }
        }
        val storeJob = SupervisorJob()
        val store = DataStoreFactory.create(
            serializer = serializer,
            scope = CoroutineScope(coroutineContext + storeJob),
            produceFile = { file },
        )
        val value = store.createValue("counter", 0)
        val beforeWrite = mutableListOf<Int>()
        val duringWrite = mutableListOf<Int>()
        val beforeCollector = backgroundScope.launch { value.flow.collect { beforeWrite.add(it) } }
        var duringCollector: Job? = null
        try {
            runCurrent()
            beforeWrite shouldBe listOf(0)

            val writer = async { value.value(1) }
            writing.await()
            duringCollector = backgroundScope.launch { value.flow.collect { duringWrite.add(it) } }
            runCurrent()
            duringWrite shouldBe listOf(0)

            releaseWrite.complete(Unit)
            writer.await()
            runCurrent()

            file.inputStream().use { PreferencesFileSerializer.readFrom(it) }[key] shouldBe 1
            value.value() shouldBe 1
            beforeWrite shouldBe listOf(0, 1)
            duringWrite shouldBe listOf(0, 1)

            value.value(2)
            runCurrent()
            beforeWrite shouldBe listOf(0, 1, 2)
            duringWrite shouldBe listOf(0, 1, 2)
        } finally {
            releaseWrite.complete(Unit)
            beforeCollector.cancelAndJoin()
            duringCollector?.cancelAndJoin()
            storeJob.cancelAndJoin()
        }
    }

    @Test
    fun `reading and writing strings`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createValue<String?>(
            key = "testKey",
            defaultValue = "default"
        ).apply {
            keyName shouldBe "testKey"

            flow.first() shouldBe "default"
            value() shouldBe "default"
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe "default"
                "newvalue"
            } shouldBe DataStoreValue.Updated("default", "newvalue")

            flow.first() shouldBe "newvalue"
            value() shouldBe "newvalue"
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe "newvalue"

            update {
                it shouldBe "newvalue"
                null
            } shouldBe DataStoreValue.Updated("newvalue", "default")

            flow.first() shouldBe "default"
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            value("newsecond")
            value() shouldBe "newsecond"
        }
    }

    @Test
    fun `reading and writing boolean`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createValue<Boolean>(
            key = "testKey",
            defaultValue = true
        ).apply {
            keyName shouldBe "testKey"

            flow.first() shouldBe true
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe true
                false
            } shouldBe DataStoreValue.Updated(old = true, new = false)

            flow.first() shouldBe false
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe false

            update {
                it shouldBe false
                null
            } shouldBe DataStoreValue.Updated(old = false, new = true)

            flow.first() shouldBe true
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe null
        }

        testStore.createValue<Boolean?>(
            key = "testKey2",
            defaultValue = null
        ).apply {
            keyName shouldBe "testKey2"

            flow.first() shouldBe null
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe null
                false
            } shouldBe DataStoreValue.Updated(old = null, new = false)

            flow.first() shouldBe false
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe false

            update {
                it shouldBe false
                null
            } shouldBe DataStoreValue.Updated(old = false, new = null)

            flow.first() shouldBe null
            testStore.data.first()[booleanPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `reading and writing long`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createValue<Long?>(
            key = "testKey",
            defaultValue = 9000L
        ).apply {
            flow.first() shouldBe 9000L
            testStore.data.first()[longPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe 9000L
                9001L
            }

            flow.first() shouldBe 9001L
            testStore.data.first()[longPreferencesKey(keyName)] shouldBe 9001L

            update {
                it shouldBe 9001L
                null
            }

            flow.first() shouldBe 9000L
            testStore.data.first()[longPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `reading and writing integer`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createValue<Long?>(
            key = "testKey",
            defaultValue = 123
        ).apply {

            flow.first() shouldBe 123
            testStore.data.first()[intPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe 123
                44
            }

            flow.first() shouldBe 44
            testStore.data.first()[intPreferencesKey(keyName)] shouldBe 44

            update {
                it shouldBe 44
                null
            }

            flow.first() shouldBe 123
            testStore.data.first()[intPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `reading and writing float`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        testStore.createValue<Float?>(
            key = "testKey",
            defaultValue = 3.6f
        ).apply {
            flow.first() shouldBe 3.6f
            testStore.data.first()[floatPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe 3.6f
                15000f
            }

            flow.first() shouldBe 15000f
            testStore.data.first()[floatPreferencesKey(keyName)] shouldBe 15000f

            update {
                it shouldBe 15000f
                null
            }

            flow.first() shouldBe 3.6f
            testStore.data.first()[floatPreferencesKey(keyName)] shouldBe null
        }
    }

}
