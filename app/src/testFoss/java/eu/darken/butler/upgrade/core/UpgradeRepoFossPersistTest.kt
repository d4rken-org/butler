package eu.darken.butler.upgrade.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The FOSS supporter record is create-only-if-absent: the sponsor-return heuristic can fire again
 * for someone who is already a supporter (the recurring-donation button, or a stale entitlement
 * replay), and a rewrite would move their "supporter since" date — the one the status screen shows.
 *
 * Driven through a real DataStore on a temp file via [FossCache]'s test seam, because the guarantee
 * is the store transaction's, not the caller's.
 */
class UpgradeRepoFossPersistTest : BaseTest() {

    @TempDir
    lateinit var tempDir: File

    // One store scope per test: the DataStore keeps its own actor alive on it.
    private var storeScope: CoroutineScope? = null

    @AfterEach
    fun teardown() {
        storeScope?.cancel()
        storeScope = null
    }

    private class Harness(
        val dataStore: DataStore<Preferences>,
        val cache: FossCache,
        val repo: UpgradeRepoFoss,
    )

    private fun newStoreScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()).also { storeScope = it }

    // Unique file name per test method: DataStore forbids two active instances on the same file.
    private fun buildHarness(storeName: String): Harness {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = newStoreScope(),
            produceFile = { File(tempDir, "$storeName.preferences_pb") },
        )
        // The real DI Json config: FossUpgrade's upgradedAt needs the contextual Instant serializer.
        val cache = FossCache(dataStore, SerializationIOModule().json())
        val repo = UpgradeRepoFoss(
            fossCache = cache,
            webpageTool = mockk<WebpageTool>(relaxed = true),
        )
        return Harness(dataStore, cache, repo)
    }

    @Test
    fun `persistUpgrade keeps an existing record`() = runTest {
        val harness = buildHarness("existing_record")
        // The epoch date is the regression payload: a rewrite would move the "supporter since" date
        // an existing supporter is being shown.
        harness.cache.upgrade.value(
            FossUpgrade(
                upgradedAt = Instant.fromEpochMilliseconds(0),
                upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
            )
        )

        harness.repo.persistUpgrade() shouldBe false

        harness.cache.upgrade.value() shouldBe FossUpgrade(
            upgradedAt = Instant.fromEpochMilliseconds(0),
            upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
        )
        harness.repo.upgradeInfo.first().apply {
            isPro shouldBe true
            upgradedAt shouldBe Instant.fromEpochMilliseconds(0)
        }
    }

    @Test
    fun `persistUpgrade creates on an empty store`() = runTest {
        val harness = buildHarness("empty_store")
        harness.cache.upgrade.value() shouldBe null

        // Plain, untruncated: InstantSerializer is ISO-8601 and preserves sub-millisecond precision,
        // so the stored value can be compared against a precise bracket.
        val before = Clock.System.now()
        harness.repo.persistUpgrade() shouldBe true
        val after = Clock.System.now()

        val created = harness.cache.upgrade.value()
        created shouldNotBe null
        created!!.upgradeType shouldBe FossUpgrade.Type.GITHUB_SPONSORS
        (created.upgradedAt >= before) shouldBe true
        (created.upgradedAt <= after) shouldBe true

        // Boolean-proven keep: immune to a timestamp collision between the two writes.
        harness.repo.persistUpgrade() shouldBe false
        harness.cache.upgrade.value() shouldBe created
    }

    @Test
    fun `concurrent persists elect exactly one creator`() = runTest {
        val harness = buildHarness("concurrent")
        harness.cache.upgrade.value() shouldBe null

        val before = Clock.System.now()
        val gate = CompletableDeferred<Unit>()
        val racers = List(2) {
            async(Dispatchers.IO) {
                gate.await()
                harness.repo.persistUpgrade()
            }
        }
        gate.complete(Unit)
        val results = racers.awaitAll()
        val after = Clock.System.now()

        // Exactly one creator: the loser must report the record it found, not a second creation.
        results.sorted() shouldBe listOf(false, true)

        val record = harness.cache.upgrade.value()
        record shouldNotBe null
        record!!.upgradeType shouldBe FossUpgrade.Type.GITHUB_SPONSORS
        (record.upgradedAt >= before) shouldBe true
        (record.upgradedAt <= after) shouldBe true
    }

    @Test
    fun `an undecodable record fails the persist and stays untouched`() = runTest {
        // [FossCache] passes an explicit `onErrorFallbackToDefault = false`, so an undecodable stored
        // record does NOT read as absent — the transaction throws and the persist fails. That is the
        // deliberate ground truth: there is no clobber path that could replace a supporter's record
        // just because it failed to parse.
        val harness = buildHarness("undecodable")
        val rawKey = stringPreferencesKey("foss.upgrade")
        val malformed = """{ not valid json }"""
        harness.dataStore.edit { prefs -> prefs[rawKey] = malformed }

        // Supertype: a decode failure is not uniformly a SerializationException — a contextual
        // serializer can reject syntactically valid JSON with an IllegalArgumentException.
        shouldThrow<Exception> { harness.repo.persistUpgrade() }

        // Untouched: the raw string is still the one that failed to decode.
        harness.dataStore.data.first()[rawKey] shouldBe malformed
    }
}
