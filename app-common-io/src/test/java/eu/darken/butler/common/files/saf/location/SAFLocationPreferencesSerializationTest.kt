package eu.darken.butler.common.files.saf.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.datastore.createValue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import java.io.File

class SAFLocationPreferencesSerializationTest : BaseTest() {

    private val testFiles = mutableListOf<File>()

    private fun createDataStore(scope: TestScope): DataStore<Preferences> {
        val testFile = File(
            IO_TEST_BASEDIR,
            "${SAFLocationPreferencesSerializationTest::class.java.simpleName}_${System.nanoTime()}.preferences_pb"
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
    fun `single LocationPreference with all fields set`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val testData = LocationPreference(
            locationId = "test-id-123",
            userLabel = "My SD Card",
            isHidden = true
        )

        testStore.createValue(
            key = "test.location.preference",
            defaultValue = LocationPreference("default"),
            json = json
        ).apply {
            update { testData }
            flow.first() shouldBe testData

            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "locationId": "test-id-123",
                    "userLabel": "My SD Card",
                    "isHidden": true
                }
            """.toComparableJson()
        }
    }

    @Test
    fun `single LocationPreference with defaults`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val testData = LocationPreference(
            locationId = "test-id-456"
        )

        testStore.createValue(
            key = "test.location.preference.defaults",
            defaultValue = LocationPreference("default"),
            json = json
        ).apply {
            update { testData }
            flow.first() shouldBe testData

            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "locationId": "test-id-456",
                    "userLabel": null,
                    "isHidden": false
                }
            """.toComparableJson()
        }
    }

    @Test
    fun `single LocationPreference without encodeDefaults`() = runTest {
        val testStore = createDataStore(this)
        val json = Json

        val testData = LocationPreference(
            locationId = "test-id-789",
            userLabel = "External Storage",
            isHidden = false
        )

        testStore.createValue(
            key = "test.location.preference.no.defaults",
            defaultValue = LocationPreference("default"),
            json = json
        ).apply {
            // Update to trigger serialization
            update { testData }
            flow.first() shouldBe testData

            // Without encodeDefaults, null and false should be omitted
            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "locationId": "test-id-789",
                    "userLabel": "External Storage"
                }
            """.toComparableJson()
        }
    }

    @Test
    fun `Map of LocationPreferences serialization`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val testData = mapOf(
            "location-1" to LocationPreference(
                locationId = "location-1",
                userLabel = "Primary Storage",
                isHidden = false
            ),
            "location-2" to LocationPreference(
                locationId = "location-2",
                userLabel = null,
                isHidden = true
            ),
            "location-3" to LocationPreference(
                locationId = "location-3",
                userLabel = "Work Files",
                isHidden = false
            )
        )

        testStore.createValue<Map<String, LocationPreference>>(
            key = "test.location.map",
            defaultValue = emptyMap(),
            json = json
        ).apply {
            update { testData }
            flow.first() shouldBe testData

            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "location-1": {
                        "locationId": "location-1",
                        "userLabel": "Primary Storage",
                        "isHidden": false
                    },
                    "location-2": {
                        "locationId": "location-2",
                        "userLabel": null,
                        "isHidden": true
                    },
                    "location-3": {
                        "locationId": "location-3",
                        "userLabel": "Work Files",
                        "isHidden": false
                    }
                }
            """.toComparableJson()
        }
    }

    @Test
    fun `Map update and removal operations`() = runTest {
        val testStore = createDataStore(this)
        val json = Json { encodeDefaults = true }

        val initial = mapOf(
            "loc-1" to LocationPreference("loc-1", "Label 1", false)
        )

        testStore.createValue<Map<String, LocationPreference>>(
            key = "test.location.map.ops",
            defaultValue = emptyMap(),
            json = json
        ).apply {
            // Initial state
            flow.first() shouldBe emptyMap()

            // Add location
            update { initial }
            flow.first() shouldBe initial

            // Update location label
            val updated = mapOf(
                "loc-1" to LocationPreference("loc-1", "Updated Label", false)
            )
            update { updated }
            flow.first() shouldBe updated

            // Add second location
            val withTwo = updated + ("loc-2" to LocationPreference("loc-2", "Label 2", true))
            update { withTwo }
            flow.first() shouldBe withTwo

            // Remove first location
            val onlySecond = mapOf("loc-2" to LocationPreference("loc-2", "Label 2", true))
            update { onlySecond }
            flow.first() shouldBe onlySecond

            // Clear all
            update { emptyMap() }
            flow.first() shouldBe emptyMap()
        }
    }

    @Test
    fun `backward compatibility - missing optional fields`() = runTest {
        val testStore = createDataStore(this)
        val json = Json

        // Simulate old format with minimal fields (only locationId)
        val minimalJson = """{"locationId": "backward-compat-id"}"""

        // Manually set the raw JSON
        testStore.createValue(
            key = "test.backward.compat",
            defaultValue = LocationPreference("default-id"),
            json = json
        ).apply {
            // Write minimal JSON directly to the store
            testStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(stringPreferencesKey(keyName), minimalJson)
                }.toPreferences()
            }

            // Should deserialize with defaults
            val result = flow.first()
            result.locationId shouldBe "backward-compat-id"
            result.userLabel shouldBe null
            result.isHidden shouldBe false
        }
    }
}
