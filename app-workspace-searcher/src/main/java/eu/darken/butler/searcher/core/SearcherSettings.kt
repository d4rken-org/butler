package eu.darken.butler.searcher.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearcherSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_searcher")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val caseSensitive = dataStore.createValue("searcher.case_sensitive", false)
    val wholeWord = dataStore.createValue("searcher.whole_word", false)
    val useRegex = dataStore.createValue("searcher.use_regex", false)
    val maxHistoryItems = dataStore.createValue("searcher.max_history_items", 50)
    val saveHistory = dataStore.createValue("searcher.save_history", true)
    val maxSearchResults = dataStore.createValue("searcher.max_search_results", 1000)

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        caseSensitive,
        wholeWord,
        useRegex,
        maxHistoryItems,
        saveHistory,
        maxSearchResults,
    )

    companion object {
        internal val TAG = logTag("Searcher", "Settings")
    }
}