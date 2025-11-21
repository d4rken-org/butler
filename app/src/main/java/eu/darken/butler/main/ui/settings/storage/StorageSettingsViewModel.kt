package eu.darken.butler.main.ui.settings.storage

import android.os.Parcelable
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.recyclebin.RecycleBinSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.parcelize.Parcelize
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

@HiltViewModel
class StorageSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val generalSettings: GeneralSettings,
    private val imageLoader: ImageLoader,
    private val recycleBinSettings: RecycleBinSettings,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Storage", "ViewModel"), navCtrl) {

    private val refreshTrigger = MutableStateFlow(0)

    val state = combine(
        flowOf(Unit),
        refreshTrigger.onStart { refreshCacheStats() },
        recycleBinSettings.enabled.flow,
        recycleBinSettings.expiresAfter.flow,
        recycleBinSettings.maxRecycleBinSize.flow,
    ) { values: Array<Any> ->
        State(
            previewDiskCacheSize = imageLoader.diskCache?.size ?: 0L,
            previewMemoryCacheSize = imageLoader.memoryCache?.size ?: 0L,
            recycleBinEnabled = values[2] as Boolean,
            recycleBinAutoDeleteDays = values[3] as Int,
            recycleBinMaxSizeMB = values[4] as Long,
        )
    }.asStateFlow()

    fun clearPreviewDiskCache() = launch {
        log(tag) { "clearPreviewDiskCache()" }
        try {
            imageLoader.diskCache?.clear()
            log(tag, INFO) { "Disk cache cleared successfully" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to clear disk cache: $e" }
        }
        refreshCacheStats()
    }

    fun clearPreviewMemoryCache() = launch {
        log(tag) { "clearPreviewMemoryCache()" }
        try {
            imageLoader.memoryCache?.clear()
            log(tag, INFO) { "Memory cache cleared successfully" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to clear memory cache: $e" }
        }
        refreshCacheStats()
    }

    fun clearAllPreviewCaches() = launch {
        log(tag) { "clearAllPreviewCaches()" }
        try {
            imageLoader.diskCache?.clear()
            imageLoader.memoryCache?.clear()
            log(tag, INFO) { "All caches cleared successfully" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to clear caches: $e" }
        }
        refreshCacheStats()
    }

    fun refreshCacheStats() = launch {
        log(tag) { "refreshCacheStats()" }
        refreshTrigger.value++
    }

    fun toggleRecycleBin(enabled: Boolean) = launch {
        log(tag) { "toggleRecycleBin($enabled)" }
        recycleBinSettings.enabled.value(enabled)
    }

    // TODO implement
    fun setRecycleBinAutoDeleteDays(days: Int) = launch {
        log(tag) { "setRecycleBinAutoDeleteDays($days)" }
        recycleBinSettings.expiresAfter.value(days.days)
    }

    // TODO implement
    fun setRecycleBinMaxSizeMB(sizeMB: Long) = launch {
        log(tag) { "setRecycleBinMaxSizeMB($sizeMB)" }
        recycleBinSettings.maxRecycleBinSize.value(sizeMB * 1048576L)
    }

    @Parcelize
    data class State(
        val previewDiskCacheSize: Long,
        val previewMemoryCacheSize: Long,
        val recycleBinEnabled: Boolean = true,
        val recycleBinAutoDeleteDays: Int = 30,
        val recycleBinMaxSizeMB: Long = 500L,
    ) : Parcelable
}
