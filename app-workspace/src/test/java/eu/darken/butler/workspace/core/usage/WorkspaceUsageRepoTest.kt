package eu.darken.butler.workspace.core.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.serialization.InstantSerializer
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant

class WorkspaceUsageRepoTest : BaseTest() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = SerializersModule { contextual(InstantSerializer) }
    }

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val key = stringPreferencesKey("workspace.usage.types")

    private lateinit var dataStore: DataStore<Preferences>

    @AfterEach
    fun cleanup() {
        storeScope.cancel()
    }

    private fun createStore(tempDir: File): DataStore<Preferences> {
        dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tempDir, "workspace_usage_${System.nanoTime()}.preferences_pb") },
        )
        return dataStore
    }

    private fun createRepo(tempDir: File): WorkspaceUsageRepo {
        val store = createStore(tempDir)
        val usageValue = store.createValue(
            "workspace.usage.types",
            WorkspaceUsageData(),
            json,
            onErrorFallbackToDefault = true,
        )
        val settings = mockk<WorkspaceUsageSettings>().apply {
            every { usageData } returns usageValue
        }
        return WorkspaceUsageRepo(settings)
    }

    private fun instant(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds)

    @Test
    fun `types are ranked by use count first`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)

        repo.track(Workspace.Type.EXPLORER, instant(100))
        repo.track(Workspace.Type.SEARCHER, instant(200))
        repo.track(Workspace.Type.SEARCHER, instant(300))

        repo.rankedTypes.first() shouldBe listOf(Workspace.Type.SEARCHER, Workspace.Type.EXPLORER)
    }

    @Test
    fun `equal use counts are broken by last used`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)

        repo.track(Workspace.Type.EXPLORER, instant(100))
        repo.track(Workspace.Type.SEARCHER, instant(500))
        repo.track(Workspace.Type.EDITOR, instant(300))

        repo.rankedTypes.first() shouldBe listOf(
            Workspace.Type.SEARCHER,
            Workspace.Type.EDITOR,
            Workspace.Type.EXPLORER,
        )
    }

    @Test
    fun `unknown persisted type names are dropped`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(
                WorkspaceUsageData(
                    entries = listOf(
                        WorkspaceTypeUsage(type = "SOME_REMOVED_TYPE", useCount = 99, lastUsed = instant(900)),
                        WorkspaceTypeUsage(type = Workspace.Type.EXPLORER.name, useCount = 1, lastUsed = instant(100)),
                    )
                )
            )
        }

        repo.rankedTypes.first() shouldBe listOf(Workspace.Type.EXPLORER)
    }

    @Test
    fun `tracking is capped at the tracking limit`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)
        val padding = (1..25).map {
            WorkspaceTypeUsage(type = "PADDING_$it", useCount = 1, lastUsed = instant(it.toLong()))
        }
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(WorkspaceUsageData(entries = padding))
        }

        repo.track(Workspace.Type.EXPLORER, instant(1000))

        val stored = json.decodeFromString<WorkspaceUsageData>(dataStore.data.first()[key]!!)
        stored.entries.size shouldBe 20
        stored.entries.first().type shouldBe Workspace.Type.EXPLORER.name
    }

    @Test
    fun `concurrent tracking keeps every increment`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)

        withContext(Dispatchers.Default) {
            (1..50)
                .map { async { repo.track(Workspace.Type.EXPLORER, instant(it.toLong())) } }
                .awaitAll()
        }

        val stored = json.decodeFromString<WorkspaceUsageData>(dataStore.data.first()[key]!!)
        stored.entries.single().useCount shouldBe 50
    }

    @Test
    fun `a malformed persisted blob degrades to an empty ranking`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)
        dataStore.edit { prefs -> prefs[key] = "{ not json }" }

        repo.rankedTypes.first() shouldBe emptyList()

        repo.track(Workspace.Type.EXPLORER, instant(100))
        repo.rankedTypes.first() shouldBe listOf(Workspace.Type.EXPLORER)
    }

    @Test
    fun `a write failure is swallowed`() = runTest {
        val usageValue = mockk<DataStoreValue<WorkspaceUsageData>>().apply {
            every { flow } returns flowOf(WorkspaceUsageData())
            coEvery { update(any()) } throws IllegalStateException("Disk on fire")
        }
        val settings = mockk<WorkspaceUsageSettings>().apply {
            every { usageData } returns usageValue
        }
        val repo = WorkspaceUsageRepo(settings)

        repo.track(Workspace.Type.EXPLORER, instant(100))

        // The write was attempted and its failure did not escape
        coVerify(exactly = 1) { usageValue.update(any()) }
        repo.rankedTypes.first() shouldBe emptyList()
    }

    @Test
    fun `tracking an existing type updates its timestamp`(@TempDir tempDir: File) = runTest {
        val repo = createRepo(tempDir)

        repo.track(Workspace.Type.EXPLORER, instant(100))
        repo.track(Workspace.Type.EXPLORER, instant(400))

        val stored = json.decodeFromString<WorkspaceUsageData>(dataStore.data.first()[key]!!)
        stored.entries.single().useCount shouldBe 2
        stored.entries.single().lastUsed shouldBe instant(400)
    }

    @Test
    fun `default value is empty`(@TempDir tempDir: File) = runTest {
        val store = createStore(tempDir)
        val usageValue = store.createValue(
            "workspace.usage.types",
            WorkspaceUsageData(),
            json,
            onErrorFallbackToDefault = true,
        )

        usageValue.value() shouldBe WorkspaceUsageData()
    }
}
