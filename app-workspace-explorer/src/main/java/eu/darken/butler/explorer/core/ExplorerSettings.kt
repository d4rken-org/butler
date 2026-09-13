package eu.darken.butler.explorer.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.serialization.createAPathListValue
import eu.darken.butler.explorer.core.favorites.FavoriteEntry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExplorerSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_explorer")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val sortSettings = dataStore.createValue("explorer.sort.default", SortSettings(), json)
    val useRegexPatterns = dataStore.createValue("explorer.filter.regex.enabled", false)
    val useBackButtonForNavigation = dataStore.createValue("explorer.navigation.use_back_button", true)

    val defaultViewStyle = dataStore.createValue("explorer.view.style.default", ExplorerViewStyle.default(), json)

    val defaultStartLocation = dataStore.createValue<DefaultStartLocation?>("explorer.navigation.start_location_default", null, json)

    /** Superseded by [favoriteEntries]; still read once to carry a pre-label list over. */
    val favoritePaths = dataStore.createAPathListValue("explorer.favorites.paths", emptyList(), json)

    private val favoriteEntriesSerializer = ListSerializer(FavoriteEntry.serializer())

    /**
     * Three states the reified [createValue] cannot express, hence the hand-written pair:
     * an absent key reads as `null` and falls back to [favoritePaths], a payload that does not
     * decode reads as an empty list, and anything else is the stored list. Corruption must not
     * collapse into absence, or it would resurrect a legacy list the user has since edited.
     */
    val favoriteEntries: DataStoreValue<List<FavoriteEntry>?> = dataStore.createValue(
        key = stringPreferencesKey("explorer.favorites.entries"),
        reader = { rawValue: Any? ->
            (rawValue as? String)?.let { encoded ->
                try {
                    json.decodeFromString(favoriteEntriesSerializer, encoded)
                } catch (e: SerializationException) {
                    log(TAG, WARN) { "Failed to decode favorite entries, starting empty: ${e.message}" }
                    emptyList()
                } catch (e: IllegalArgumentException) {
                    // LocalPath/SAFPath constructors use require(), which throws IAE for
                    // syntactically valid JSON that fails semantic validation.
                    log(TAG, WARN) { "Invalid favorite entry payload, starting empty: ${e.message}" }
                    emptyList()
                }
            }
        },
        writer = { value: List<FavoriteEntry>? ->
            value?.let { json.encodeToString(favoriteEntriesSerializer, it) }
        },
    )

    // Nonessential preference: a corrupt/unknown persisted value must never block archive creation.
    val compressDefaults = dataStore.createValue(
        "explorer.compress.defaults",
        ArchiveCompressionDefaults(),
        json,
        onErrorFallbackToDefault = true,
    )

    override val mapper = PreferenceStoreMapper(
        sortSettings,
        useRegexPatterns,
        useBackButtonForNavigation,
        defaultViewStyle,
        defaultStartLocation,
        favoritePaths,
        favoriteEntries,
        compressDefaults,
    )

    companion object {
        internal val TAG = logTag("Explorer", "Settings")
    }
}