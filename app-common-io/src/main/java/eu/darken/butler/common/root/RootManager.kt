package eu.darken.butler.common.root

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.root.service.RootServiceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    val serviceClient: RootServiceClient,
    settings: RootSettings,
) {

    val binder: Flow<RootServiceClient.Connection?> = settings.useRoot.flow
        .flatMapLatest {
            if (it != true) return@flatMapLatest emptyFlow()

            callbackFlow<RootServiceClient.Connection?> {
                val resource = serviceClient.get()
                send(resource.item)
                awaitClose {
                    log(TAG) { "Closing binder resource" }
                    resource.close()
                }
            }
        }
        .catch {
            log(TAG, WARN) { "RootServiceClient.Connection was unavailable" }
            emit(null)
        }
        .setupCommonEventHandlers(TAG) { "binder" }
        .onEach {
            log(TAG, VERBOSE) { "Root binder changed (${it != null}), invalidating caches" }
            cacheLock.withLock {
                cachedState = null
            }
        }
        .replayingShare(appScope)

    private val cacheLock = Mutex()
    private var cachedState: Boolean? = null

    /**
     * Is the device rooted and we have access?
     */
    suspend fun isRooted(): Boolean = withContext(dispatcherProvider.IO) {
        cacheLock.withLock {
            cachedState?.let { return@withContext it }

            val newState = try {
                serviceClient.get().use { IpcContract.isCompatible(it.item.ipc.checkBase()) }
            } catch (e: CancellationException) {
                throw e // don't cache a cancelled probe as "not rooted"
            } catch (e: Exception) {
                log(TAG, WARN) { "Error while checking for root: $e" }
                false
            }

            // checkBase() is blocking IPC: cancellation during the call isn't observed until the next
            // suspension, so a probe that raced with cancellation (e.g. binder killed by our own
            // teardown) can produce a bogus negative — never cache it.
            currentCoroutineContext().ensureActive()
            newState.also { cachedState = it }
        }
    }

    /**
     * Did the user consent to Butler using root and is root available?
     */
    val useRoot: Flow<Boolean> = settings.useRoot.flow
        .mapLatest { (it ?: false) && isRooted() }
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 60 * 1000,
                replayExpirationMillis = 0,
            ),
            initialValue = null
        )
        .filterNotNull()

    suspend fun isInstalled(): Boolean {
        val installed = KNOWN_ROOT_MANAGERS.any {
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(it, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        log(TAG) { "isInstalled(): $installed" }
        return installed
    }

    companion object {
        internal val TAG = logTag("Root", "Manager")
        private val KNOWN_ROOT_MANAGERS = setOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
        )
    }
}
