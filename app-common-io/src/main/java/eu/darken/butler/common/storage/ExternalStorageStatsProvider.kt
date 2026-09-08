package eu.darken.butler.common.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@Reusable
class ExternalStorageStatsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageEnvironment: StorageEnvironment,
    private val storageManager: StorageManager2,
    private val userManager: UserManager2,
    private val gatewaySwitch: GatewaySwitch,
    private val statsManager: StorageStatsManager,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: Clock,
) {
    data class Target(
        val root: LocalPath,
        val storageUuid: Uuid,
        val user: UserHandle2,
        val canonicalRoot: LocalPath = root,
    )
    data class Snapshot(val totalBytes: Long, val queriedAt: Instant)

    suspend fun prepare(root: APath<*>): Target? = withContext(dispatcherProvider.IO) {
        if (root !is LocalPath || !hasApiLevel(30)) return@withContext null
        if (!Permission.PACKAGE_USAGE_STATS.isGranted(context)) return@withContext null
        if (!Permission.MANAGE_EXTERNAL_STORAGE.isGranted(context)) return@withContext null
        val user = userManager.currentUser().handle
        val primary = storageEnvironment.getPublicPrimaryStorage(user) ?: return@withContext null
        val volume = storageManager.getStorageVolume(primary.file) ?: return@withContext null
        if (!volume.isPrimary || !volume.isEmulated) return@withContext null
        val canonicalPrimary = gatewaySwitch.canonicalize(primary) as? LocalPath ?: return@withContext null
        if (gatewaySwitch.canonicalize(root) != canonicalPrimary) return@withContext null
        val uuid = storageManager.getUuidForPath(primary.file)
        Target(root, uuid, user, canonicalPrimary)
    }

    suspend fun query(target: Target): Snapshot = withContext(dispatcherProvider.IO) {
        val stats = statsManager.queryExternalStatsForUser(target.storageUuid.toJavaUuid(), target.user.asUserHandle())
        currentCoroutineContext().ensureActive()
        Snapshot(stats.totalBytes, clock.now())
    }
}
