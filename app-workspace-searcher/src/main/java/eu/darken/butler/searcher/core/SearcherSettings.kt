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
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_searcher")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val caseSensitive = dataStore.createValue("searcher.case_sensitive", false)
    val wholeWord = dataStore.createValue("searcher.whole_word", false)
    val useRegex = dataStore.createValue("searcher.use_regex", false)
    val searchContent = dataStore.createValue("searcher.search_content", false)

    val defaultSearchTargets = dataStore.createValue<List<SearchTarget>?>("searcher.default.targets", null, json)
    val maxSearchResults = dataStore.createValue("searcher.results.maximum", 1000)
    val saveHistory = dataStore.createValue("searcher.history.enabled", true)
    val maxHistoryItems = dataStore.createValue("searcher.history.maximum", 50)

    val sortSettings = dataStore.createValue("searcher.sort.default", SearchSortSettings(), json)

    val defaultViewStyle = dataStore.createValue("searcher.view.style.default", SearcherViewStyle.default(), json)

    val contentSearchMaxFileSize = dataStore.createValue(
        "searcher.content.max_file_size",
        10_485_760L, // 10MB
    )

    val contentSearchBufferSize = dataStore.createValue(
        "searcher.content.buffer_size",
        131_072, // 128KB
    )

    val contentSearchSkipBinary = dataStore.createValue(
        "searcher.content.skip_binary",
        true,
    )

    val contentSearchTextExtensions = dataStore.createValue(
        "searcher.content.text_extensions",
        setOf(
            "txt", "log", "md", "markdown", "rst",
            "json", "xml", "yaml", "yml", "toml", "ini", "conf", "config",
            "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp",
            "html", "css", "scss", "sass", "less",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
            "sql", "gradle", "properties", "env",
        ),
        json,
    )

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        caseSensitive,
        wholeWord,
        useRegex,
        searchContent,
        maxHistoryItems,
        saveHistory,
        maxSearchResults,
        defaultSearchTargets,
        sortSettings,
        defaultViewStyle,
        contentSearchMaxFileSize,
        contentSearchBufferSize,
        contentSearchSkipBinary,
        contentSearchTextExtensions,
    )

    companion object {
        internal val TAG = logTag("Searcher", "Settings")
    }
}