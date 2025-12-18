package eu.darken.butler.common.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import java.io.File

class DataStoreValueSerializationTest : BaseTest() {

    private fun createDataStore(scope: TestScope, tempDir: File): DataStore<Preferences> {
        val testFile = File(
            tempDir,
            "${DataStoreValueSerializationTest::class.java.simpleName}_${System.nanoTime()}.preferences_pb"
        )
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile },
        )
    }

    @Serializable
    data class TestJson(
        val list: List<String> = listOf("1", "2"),
        val string: String = "",
        val boolean: Boolean = true,
        val float: Float = 1.0f,
        val int: Int = 1,
        val long: Long = 1L
    )

    @Test
    fun `reading and writing using manual reader and writer`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val testData1 = TestJson(string = "teststring")
        val testData2 = TestJson(string = "update")
        val json = Json { encodeDefaults = true }

        testStore.createValue<TestJson?>(
            key = stringPreferencesKey("testKey"),
            reader = kotlinxSerializationReader(json, testData1),
            writer = kotlinxSerializationWriter(json)
        ).apply {

            flow.first() shouldBe testData1
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe testData1
                it!!.copy(string = "update")
            }

            flow.first() shouldBe testData2
            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "list": [
                        "1",
                        "2"
                    ],
                    "string":"update",
                    "boolean":true,
                    "float":1.0,
                    "int":1,
                    "long":1
                }
            """.toComparableJson()

            update {
                it shouldBe testData2
                null
            }

            flow.first() shouldBe testData1
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `reading and writing using autocreated reader and writer`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)

        val testData1 = TestJson(string = "teststring")
        val testData2 = TestJson(string = "update")
        val json = Json { encodeDefaults = true }

        testStore.createValue<TestJson?>(
            key = "testKey",
            defaultValue = testData1,
            json = json
        ).apply {

            flow.first() shouldBe testData1
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

            update {
                it shouldBe testData1
                it!!.copy(string = "update")
            }

            flow.first() shouldBe testData2
            testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "list": [
                        "1",
                        "2"
                    ],
                    "string":"update",
                    "boolean":true,
                    "float":1.0,
                    "int":1,
                    "long":1
                }
            """.toComparableJson()

            update {
                it shouldBe testData2
                null
            }

            flow.first() shouldBe testData1
            testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
        }
    }

    @Test
    fun `reading and writing using autocreated reader and writer without encodeDefaults flag`(@TempDir tempDir: File) =
        runTest {
            val testStore = createDataStore(this, tempDir)

            val testData1 = TestJson(
                list = listOf("7", "8"),
                string = "teststring",
                boolean = false,
                float = 3.5f,
                int = 55,
                long = 888L
            )
            val testData2 = TestJson(
                list = listOf("9", "10"),
                string = "update",
                boolean = false,
                float = 4.2f,
                int = 77,
                long = 999L
            )
            val json = Json

            testStore.createValue<TestJson?>(
                key = "testKeyAutoNoDefaults",
                defaultValue = testData1,
                json = json
            ).apply {

                flow.first() shouldBe testData1
                testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

                update {
                    it shouldBe testData1
                    testData2
                }

                flow.first() shouldBe testData2
                testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "list": [
                        "9",
                        "10"
                    ],
                    "string":"update",
                    "boolean":false,
                    "float":4.2,
                    "int":77,
                    "long":999
                }
            """.toComparableJson()

                update {
                    it shouldBe testData2
                    null
                }

                flow.first() shouldBe testData1
                testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
            }
        }

    @Serializable
    enum class Anum {
        @SerialName("a") A,
        @SerialName("b") B
    }

    @Test
    fun `reading and writing using manual reader and writer without encodeDefaults flag`(@TempDir tempDir: File) =
        runTest {
            val testStore = createDataStore(this, tempDir)

            val testData1 = TestJson(
                list = listOf("3", "4"),
                string = "teststring",
                boolean = false,
                float = 2.5f,
                int = 42,
                long = 999L
            )
            val testData2 = TestJson(
                list = listOf("5", "6"),
                string = "update",
                boolean = false,
                float = 3.7f,
                int = 123,
                long = 777L
            )
            val json = Json

            testStore.createValue<TestJson?>(
                key = stringPreferencesKey("testKeyNoDefaults"),
                reader = kotlinxSerializationReader(json, testData1),
                writer = kotlinxSerializationWriter(json)
            ).apply {

                flow.first() shouldBe testData1
                testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null

                update {
                    it shouldBe testData1
                    it!!.copy(
                        list = listOf("5", "6"),
                        string = "update",
                        boolean = false,
                        float = 3.7f,
                        int = 123,
                        long = 777L
                    )
                }

                flow.first() shouldBe testData2
                testStore.data.first()[stringPreferencesKey(keyName)]!!.toComparableJson() shouldBe """
                {
                    "list": [
                        "5",
                        "6"
                    ],
                    "string":"update",
                    "boolean":false,
                    "float":3.7,
                    "int":123,
                    "long":777
                }
            """.toComparableJson()

                update {
                    it shouldBe testData2
                    null
                }

                flow.first() shouldBe testData1
                testStore.data.first()[stringPreferencesKey(keyName)] shouldBe null
            }
        }

    @Test
    fun `enum serialization`(@TempDir tempDir: File) = runTest {
        val enumTestFile = File(tempDir, "DataStoreValueSerializationTest_enum.preferences_pb")
        val testStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { enumTestFile },
        )

        val json = Json { encodeDefaults = true }
        val monitorMode = testStore.createValue(
            "test.enum",
            Anum.A,
            json
        )

        monitorMode.flow.first() shouldBe Anum.A
        monitorMode.update { Anum.B }
        monitorMode.flow.first() shouldBe Anum.B
    }

    @Test
    fun `malformed JSON throws exception by default`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)
        val key = stringPreferencesKey("testKey")
        val defaultValue = TestJson(string = "default")
        val json = Json

        // Write malformed JSON directly to the store
        testStore.edit { prefs ->
            prefs[key] = "{ invalid json }"
        }

        val dataStoreValue = testStore.createValue<TestJson?>(
            key = "testKey",
            defaultValue = defaultValue,
            json = json,
            onErrorFallbackToDefault = false,
        )

        shouldThrow<SerializationException> {
            dataStoreValue.flow.first()
        }
    }

    @Test
    fun `malformed JSON returns default when onErrorFallbackToDefault is true`(@TempDir tempDir: File) = runTest {
        val testStore = createDataStore(this, tempDir)
        val key = stringPreferencesKey("testKey")
        val defaultValue = TestJson(string = "default")
        val json = Json

        // Write malformed JSON directly to the store
        testStore.edit { prefs ->
            prefs[key] = "{ invalid json }"
        }

        val dataStoreValue = testStore.createValue<TestJson?>(
            key = "testKey",
            defaultValue = defaultValue,
            json = json,
            onErrorFallbackToDefault = true,
        )

        dataStoreValue.flow.first() shouldBe defaultValue
    }
}
