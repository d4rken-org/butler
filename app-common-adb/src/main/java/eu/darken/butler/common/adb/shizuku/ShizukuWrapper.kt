package eu.darken.butler.common.adb.shizuku

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Butler's view of the ADB access server, whichever of Porter or Shizuku the SDK selected. No SDK type
 * leaves this module: everything here is mapped to Butler's own types.
 */
@Singleton
class ShizukuWrapper internal constructor(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val source: AdbServerSource,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider,
    ) : this(context, dispatcherProvider, PorterServerSource(context))

    /**
     * Packages that declare an ADB access manager permission, in [MANAGER_PERMISSIONS] order.
     *
     * Detects managers via their permissions instead of fixed package names. The permission names are
     * shared across forks, so this keeps working when a fork hides its package from enumeration
     * ("Hide Shizuku from other apps") or ships under a different package name. Permissions live in a
     * global namespace, so the lookup isn't subject to the package-visibility filtering that hides the
     * app itself. Every name is tried because Shizuku+'s Plus flavor declares only its own permission
     * and just requests the stock one.
     */
    suspend fun getManagerPackages(): List<String> = withContext(dispatcherProvider.IO) {
        MANAGER_PERMISSIONS.values.flatten().mapNotNull { resolvePermissionOwner(it) }.distinct()
    }

    /** Like [getManagerPackages], limited to the managers of [backend]. */
    suspend fun getManagerPackages(backend: AdbBackend): List<String> = withContext(dispatcherProvider.IO) {
        MANAGER_PERMISSIONS.getValue(backend).mapNotNull { resolvePermissionOwner(it) }.distinct()
    }

    private fun resolvePermissionOwner(permission: String): String? = try {
        context.packageManager
            .getPermissionInfo(permission, 0)
            .packageName
            ?.takeUnless { it.isBlank() }
    } catch (e: PackageManager.NameNotFoundException) {
        log(TAG) { "resolvePermissionOwner($permission): not declared by any app" }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "resolvePermissionOwner($permission): Lookup failed: ${e.asLog()}" }
        null
    }

    /** The connected server, null while there is none. */
    val connection: Flow<AdbServer?> = source.connection
        .map { connection -> connection?.let { AdbServer(it) } }
        .distinctUntilChanged()

    /** The current connection's permission state, [AdbPermissionState.Unknown] while there is no connection. */
    val permissionState: Flow<AdbPermissionState> = source.connection
        .flatMapLatest { it?.permission ?: flowOf<AdbPermissionState>(AdbPermissionState.Unknown) }
        .distinctUntilChanged()

    internal fun currentServer(): AdbServerConnection? = source.current()

    /** Null when there is no connection, or the server failed or did not answer in time. Never a denial. */
    suspend fun isGranted(): Boolean? {
        val server = source.current()
        if (server == null) {
            log(TAG) { "isGranted()=null (no connection)" }
            return null
        }
        val state = try {
            withTimeoutOrNull(IPC_TIMEOUT_MS) { server.checkPermission() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "isGranted()=null (check failed: ${e.asLog()})" }
            return null
        }
        if (state == null) {
            log(TAG, WARN) { "isGranted()=null (server did not respond within ${IPC_TIMEOUT_MS}ms)" }
            return null
        }
        log(TAG) { "isGranted(): $state" }
        return when (state) {
            AdbPermissionState.Granted -> true
            is AdbPermissionState.Denied -> false
            AdbPermissionState.Unknown -> null
        }
    }

    /**
     * Prompts the user through the manager and suspends until they answer.
     *
     * Deliberately unbounded: the SDK drops an answer that arrives after its caller stopped waiting, so
     * a timeout here would lose a grant the user did give. The SDK returns on its own if the connection
     * is lost first.
     */
    suspend fun requestPermission(): AdbPermissionState {
        val server = source.current()
        if (server == null) {
            log(TAG, WARN) { "requestPermission(): no connection" }
            return AdbPermissionState.Unknown
        }
        log(TAG) { "requestPermission() on ${server.backend}" }
        val answer = try {
            server.requestPermission()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "requestPermission() failed: ${e.asLog()}" }
            AdbPermissionState.Unknown
        }
        log(TAG) { "requestPermission(): $answer" }
        return answer
    }

    /** Null when the answer failed or did not arrive in time, which says nothing about what is installed. */
    suspend fun availability(): AdbAvailability? {
        val availability = try {
            withTimeoutOrNull(IPC_TIMEOUT_MS) { source.availability() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "availability()=null (lookup failed: ${e.asLog()})" }
            return null
        }
        if (availability == null) {
            log(TAG, WARN) { "availability()=null (did not respond within ${IPC_TIMEOUT_MS}ms)" }
            return null
        }
        log(TAG) { "availability(): $availability" }
        return availability
    }

    companion object {
        private val TAG = logTag("ADB", "Shizuku", "Wrapper")

        // Porter first, as the SDK selects it whenever it is installed; then the stock Shizuku
        // permission, preferred over Shizuku+'s when both are declared.
        private val MANAGER_PERMISSIONS = linkedMapOf(
            AdbBackend.PORTER to listOf("eu.darken.porter.permission.API"),
            AdbBackend.SHIZUKU to listOf(
                "moe.shizuku.manager.permission.API_V23",
                "af.shizuku.plus.permission.API_V23",
            ),
        )

        /**
         * Budget for one server round-trip behind [isGranted] and [availability].
         *
         * Deliberately generous rather than tight: the job here is only to turn "never returns" into
         * "eventually gives up". A too-tight bound would report a slow-but-working server as
         * unavailable, and low-end devices under memory pressure are where both a real wedge and a slow
         * answer are most likely.
         */
        internal const val IPC_TIMEOUT_MS = 15 * 1000L
    }
}
