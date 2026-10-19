package eu.darken.butler.workspace.core.appattribution

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.attribution.AppFolder
import eu.darken.butler.common.pkgs.attribution.AppFolderRecognizer
import eu.darken.butler.common.storage.DocumentUriResolver
import eu.darken.butler.common.user.UserManager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Attributes package-named folders to the app they belong to, for the folder badges. */
@Singleton
class AppAttributor @Inject constructor(
    @AppScope appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    pkgRepo: PkgRepo,
    private val userManager: UserManager2,
    private val documentUriResolver: DocumentUriResolver,
) {

    /** Null while the inventory failed to load: no attribution beats ghosting every folder. */
    private val inventory: Flow<AppInventory?> = pkgRepo.data
        .map { data ->
            val error = data.error
            if (error != null) {
                log(TAG, WARN) { "Package inventory unavailable: ${error.asLog()}" }
                null
            } else {
                AppInventory.from(data.pkgs, userManager.currentUser().handle.handleId)
            }
        }
        .replayingShare(appScope)

    /**
     * Attributions for the folders among [candidates], keyed like [candidates].
     *
     * Starts empty and never fails, so it can't hold up or break the listing it decorates; a failed
     * pass yields no attributions until the next one. The package inventory is only collected once a
     * candidate is an app folder.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <K> attributionsFor(candidates: Flow<Map<K, APath<*>>>): Flow<Map<K, AppAttribution>> = candidates
        .map { paths ->
            try {
                recognize(paths)
            } catch (e: Exception) {
                log(TAG, WARN) { "Recognition failed: ${e.asLog()}" }
                emptyMap()
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { folders ->
            if (folders.isEmpty()) {
                flowOf(emptyMap())
            } else {
                inventory
                    .map { inventory ->
                        if (inventory == null) return@map emptyMap()
                        buildMap {
                            folders.forEach { (key, folder) -> inventory.attribute(folder)?.let { put(key, it) } }
                        }
                    }
                    .catch { e ->
                        log(TAG, WARN) { "Attribution failed: ${e.asLog()}" }
                        emit(emptyMap())
                    }
            }
        }
        .onStart { emit(emptyMap()) }
        .distinctUntilChanged()
        .flowOn(dispatcherProvider.IO)

    private fun <K> recognize(paths: Map<K, APath<*>>): Map<K, AppFolder> {
        val safRoots = mutableMapOf<String, LocalPath?>()
        return buildMap {
            paths.forEach { (key, path) ->
                if (!AppFolderRecognizer.isPackageName(path.name)) return@forEach
                val local = when (path) {
                    is LocalPath -> path
                    is SAFPath -> {
                        val rootKey = path.treeRootUri.rawUri
                        val root = if (rootKey in safRoots) {
                            safRoots[rootKey]
                        } else {
                            documentUriResolver.resolve(path.treeRootUri.toAndroidUri()).also { safRoots[rootKey] = it }
                        }
                        root?.let { LocalPath.build(File(it.path, path.segments.joinToString("/"))) }
                    }
                    else -> null
                }
                local?.let { AppFolderRecognizer.recognize(it) }?.let { put(key, it) }
            }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "AppAttributor")
    }
}
