package eu.darken.butler.main.ui.settings.previews

import android.os.Parcelable
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@HiltViewModel
class PreviewsSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val imageLoader: ImageLoader,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Previews", "ViewModel")) {

    private val refreshTrigger = MutableStateFlow(0)

    val state = combine(
        flowOf(Unit),
        refreshTrigger.onStart { refreshCacheStats() },
    ) { _, _ ->
        State(
            previewDiskCacheSize = imageLoader.diskCache?.size ?: 0L,
            previewMemoryCacheSize = imageLoader.memoryCache?.size ?: 0L,
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

    @Parcelize
    data class State(
        val previewDiskCacheSize: Long,
        val previewMemoryCacheSize: Long,
    ) : Parcelable
}
