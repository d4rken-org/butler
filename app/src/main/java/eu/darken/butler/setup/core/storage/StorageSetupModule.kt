package eu.darken.butler.setup.core.storage

import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.device.DeviceDetective
import eu.darken.butler.common.device.RomType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.permissions.Specialpermission
import eu.darken.butler.common.rngString
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class StorageSetupModule @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val storageManager2: StorageManager2,
    private val deviceDetective: DeviceDetective,
) : SetupModule {

    private val refreshTrigger = MutableStateFlow(rngString)
    override val state: Flow<SetupModule.State> = refreshTrigger
        .mapLatest {
            val requiredPermission = getRequiredPermission()
            val missingPermission = requiredPermission.filter {
                val isGranted = it.isGranted(context)
                log(TAG) { "${it.permissionId} isGranted=$isGranted" }
                !isGranted
            }.toSet()

            val affectedPaths = storageManager2.storageVolumes
                .filter { it.directory != null }
                .map { volume ->
                    Result.PathAccess(
                        label = when {
                            volume.isPrimary || volume.isEmulated -> R.string.storage_area_public_label.toCaString()
                            else -> R.string.storage_area_sdcard_label.toCaString()
                        },
                        localPath = LocalPath.build(volume.directory!!),
                        hasAccess = requiredPermission.all { it.isGranted(context) },
                    )
                }

            @Suppress("USELESS_CAST")
            Result(
                missingPermission = missingPermission,
                paths = affectedPaths,
            ) as SetupModule.State
        }
        .onStart { emit(Loading()) }
        .replayingShare(appScope)

    private fun getRequiredPermission(): Set<Permission> = when {
        deviceDetective.getROMType() == RomType.ANDROID_TV -> when {
            hasApiLevel(33) -> setOf(
                @Suppress("NewApi")
                Permission.MANAGE_EXTERNAL_STORAGE,
            )

            else -> setOf(
                Permission.WRITE_EXTERNAL_STORAGE,
                Permission.READ_EXTERNAL_STORAGE,
            )
        }

        else -> when {
            hasApiLevel(30) -> setOf(
                @Suppress("NewApi")
                Permission.MANAGE_EXTERNAL_STORAGE,
            )
            else -> setOf(
                Permission.WRITE_EXTERNAL_STORAGE,
                Permission.READ_EXTERNAL_STORAGE,
            )
        }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    suspend fun onPermissionChanged(permission: Permission, granted: Boolean) {
        log(TAG) { "onPermissionChanged($permission, $granted)" }

    }

    fun getPermissionIntent(): Intent? {
        val requiredPermissions = getRequiredPermission()
        val specialPermission = requiredPermissions.find { it is Specialpermission } as? Specialpermission

        return specialPermission?.let {
            try {
                it.createIntent(context, deviceDetective)
            } catch (e: Exception) {
                log(TAG) { "Failed to create intent: $e" }
                it.createIntentFallback(context)
            }
        }
    }

    fun getRuntimePermissions(): Set<String> {
        val requiredPermissions = getRequiredPermission()
        return requiredPermissions
            .filter { it !is Specialpermission && !it.isGranted(context) }
            .map { it.permissionId }
            .toSet()
    }

    data class Loading(
        override val startAt: Instant = Clock.System.now(),
    ) : SetupModule.State.Loading {
        override val type: SetupModule.Type = SetupModule.Type.STORAGE
    }

    data class Result(
        val paths: List<PathAccess>,
        val missingPermission: Set<Permission>,
    ) : SetupModule.State.Current {

        override val type: SetupModule.Type = SetupModule.Type.STORAGE

        override val isComplete: Boolean = missingPermission.isEmpty() && paths.all { it.hasAccess }

        data class PathAccess(
            val label: CaString,
            val localPath: LocalPath,
            val hasAccess: Boolean,
        )
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: StorageSetupModule): SetupModule
    }

    companion object {
        private val TAG = logTag("Setup", "Storage", "Module")
    }
}