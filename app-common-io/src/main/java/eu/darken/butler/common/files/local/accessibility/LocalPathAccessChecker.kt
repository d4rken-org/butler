package eu.darken.butler.common.files.local.accessibility

import android.annotation.SuppressLint
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.isDescendantOf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.storage.StorageEnvironment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks if a local path is accessible via normal Java File APIs given storage permissions.
 * Used by LocalGateway AUTO mode to optimize privilege escalation.
 */
@SuppressLint("SdCardPath")
@Singleton
class LocalPathAccessChecker @Inject constructor(
    private val storageEnvironment: StorageEnvironment,
) {

    private val publicAccessible: Set<LocalPath>
        get() = buildSet {
            storageEnvironment.publicStorages.forEach { add(it) }
            add(LocalPath.build("/sdcard"))
            add(LocalPath.build("/storage/self/primary"))
            add(LocalPath.build("/storage/emulated/0"))
        }.flatMap { aliasesOf(it) }.toSet()

    private val publicBlocked: Set<LocalPath>
        get() = if (!hasApiLevel(30)) {
            emptySet()
        } else {
            buildSet {
                addAll(storageEnvironment.publicDataDirs)
                addAll(storageEnvironment.publicObbDirs)
            }.flatMap { aliasesOf(it) }.toSet()
        }

    private val systemBlocked by lazy {
        buildSet {
            add(LocalPath.build("/dev"))
            add(LocalPath.build("/cache"))
            add(LocalPath.build("/storage"))
            add(LocalPath.build("/data"))
            add(LocalPath.build("/sys"))
        }
    }

    private val systemReadOnly by lazy {
        buildSet {
            add(LocalPath.build("/system"))
            add(LocalPath.build("/proc"))
            add(LocalPath.build("/vendor"))
            add(LocalPath.build("/product"))
            add(LocalPath.build("/system_ext"))
            add(LocalPath.build("/apex"))
        }
    }

    private val ourDirs: Set<LocalPath>
        get() = buildSet {
            addAll(storageEnvironment.ourPublicDirs)
            addAll(storageEnvironment.ourPrivateDirs)
            addAll(
                storageEnvironment.ourPrivateDirs
                    .map { it.path.replace("/data/user/0", "/data/data") }
                    .map { LocalPath.build(it) }
            )
        }.flatMap { aliasesOf(it) }.toSet()

    /**
     * Returns true if normal Java File API access should be attempted for this path.
     * Conservative: when uncertain, returns true (try normal access first, escalate on failure).
     *
     * This enables LocalGateway AUTO mode to:
     * - Skip normal access for definitely inaccessible paths (system partitions, other apps)
     * - Try normal access for potentially accessible paths (user storage, app data)
     * - Maintain correct file ownership by preferring normal access when possible
     */
    fun shouldTryNormalAccess(path: LocalPath, forWriting: Boolean): Boolean {
        if (path.path == "/") {
            // Root filesystem always requires escalation (root/ADB) on Android
            return false
        }

        if (ourDirs.any { path.isDescendantOf(it) || path.matches(it) }) {
            return true
        }

        if (publicAccessible.any { path.isDescendantOf(it) || path.matches(it) }) {
            return !publicBlocked.any { path.isDescendantOf(it) || path.matches(it) }
        }

        if (systemReadOnly.any { path.isDescendantOf(it) || path.matches(it) }) {
            return !forWriting
        }

        if (systemBlocked.any { path.isDescendantOf(it) || path.matches(it) }) {
            return false
        }

        // Conservative fallback: try normal access first for unknown paths
        // If it fails, LocalGateway will escalate to root/ADB
        return true
    }

    private fun aliasesOf(path: LocalPath): Set<LocalPath> = buildSet {
        add(path)

        val primaryAliases = listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/self/primary"),
            LocalPath.build("/storage/emulated/0"),
        )

        primaryAliases.forEach { from ->
            if (path.matches(from) || path.isDescendantOf(from)) {
                val suffix = if (path.matches(from)) {
                    emptyList()
                } else {
                    path.path
                        .removePrefix(from.path)
                        .trim('/')
                        .split('/')
                        .filter { it.isNotEmpty() }
                }
                primaryAliases.forEach { to ->
                    add(if (suffix.isEmpty()) to else to.child(*suffix.toTypedArray()))
                }
            }
        }
    }
}
