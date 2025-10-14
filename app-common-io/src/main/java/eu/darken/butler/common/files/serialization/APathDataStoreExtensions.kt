package eu.darken.butler.common.files.serialization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.files.APath
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json

/**
 * Creates a DataStoreValue for storing APath instances with polymorphic serialization support.
 *
 * This extension handles the complexity of serializing APath with its self-referencing generic type
 * (APath<out Self : APath<Self>>) by using PolymorphicSerializer instead of reified generics.
 *
 * **When to use:**
 * - Use this for DataStore preferences that need to store APath values
 * - The function handles all APath implementations (LocalPath, SAFPath, RawPath) automatically
 *
 * **When NOT to use:**
 * - Regular @Serializable data classes with APath<*> fields work fine without this helper
 * - Only DataStore's createValue<T>() with reified generics has issues with APath<*>
 *
 * @param key The preference key name
 * @param defaultValue The default value to use when no value is stored (defaults to null)
 * @param json The Json instance configured with necessary serializers
 * @return A DataStoreValue that can read/write APath instances
 *
 * Example usage:
 * ```kotlin
 * class MySettings @Inject constructor(
 *     @ApplicationContext private val context: Context,
 *     private val json: Json,
 * ) {
 *     val somePath = dataStore.createAPathValue("my.path", null, json)
 * }
 * ```
 */
fun DataStore<Preferences>.createAPathValue(
    key: String,
    defaultValue: APath<*>? = null,
    json: Json,
): DataStoreValue<APath<*>?> = createValue(
    key = stringPreferencesKey(key),
    reader = { rawValue: Any? ->
        (rawValue as? String)?.let {
            json.decodeFromString(PolymorphicSerializer(APath::class), it)
        } ?: defaultValue
    },
    writer = { value: APath<*>? ->
        value?.let { json.encodeToString(PolymorphicSerializer(APath::class), it) }
    }
)
