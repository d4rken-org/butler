package eu.darken.butler.provider.documents.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.pkgs.toggleSelfComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the enabled/disabled state of the DocumentsProvider component.
 *
 * Synchronizes the PackageManager component state with the user's setting preference,
 * ensuring the provider is properly registered or hidden from the system.
 */
@Singleton
class DocumentsProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val packageManager: PackageManager,
    private val settings: DocumentsProviderSettings,
) {

    private val providerComponent = ComponentName(
        context,
        ButlerDocumentsProvider::class.java
    )

    init {
        log(TAG, INFO) { "Initializing DocumentsProviderManager" }

        settings.isEnabled.flow
            .onEach { enabled ->
                log(TAG, INFO) { "Provider enabled setting changed to: $enabled" }
                syncComponentState(enabled)
            }
            .setupCommonEventHandlers(TAG) { "isEnabled.flow" }
            .launchIn(appScope)
    }

    private fun syncComponentState(enabled: Boolean) {
        try {
            packageManager.toggleSelfComponent(providerComponent, enabled)
            log(TAG, INFO) { "Provider component state synced: enabled=$enabled" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to sync provider component state: $e" }
        }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Manager")
    }
}
