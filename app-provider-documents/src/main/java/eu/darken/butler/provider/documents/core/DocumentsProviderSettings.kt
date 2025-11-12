package eu.darken.butler.provider.documents.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentsProviderSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_documents_provider")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    // Phase 1: Basic settings
    val isEnabled = dataStore.createValue("provider.enabled", true)

    // Phase 2+: Root visibility settings
    val showRootFilesystem = dataStore.createValue("provider.roots.show_root_filesystem", true)
    val showInternalStorage = dataStore.createValue("provider.roots.show_internal_storage", true)
    val showExternalStorage = dataStore.createValue("provider.roots.show_external_storage", true)
    val showSAFTrees = dataStore.createValue("provider.roots.show_saf_trees", false)

    // Phase 2+: Advanced settings
    val requireRootAccess = dataStore.createValue("provider.root_access.require", false)
    val requireADBAccess = dataStore.createValue("provider.adb_access.require", false)

    // Phase 3+: Performance settings
    val thumbnailCacheEnabled = dataStore.createValue("provider.thumbnails.cache_enabled", true)
    val maxCachedThumbnails = dataStore.createValue("provider.thumbnails.max_cached", 100)

    override val mapper = PreferenceStoreMapper(
        // Phase 1
        isEnabled,

        // Phase 2+
        showRootFilesystem,
        showInternalStorage,
        showExternalStorage,
        showSAFTrees,
        requireRootAccess,
        requireADBAccess,

        // Phase 3+
        thumbnailCacheEnabled,
        maxCachedThumbnails,
    )

    companion object {
        internal val TAG = logTag("Provider", "Documents", "Settings")
    }
}
