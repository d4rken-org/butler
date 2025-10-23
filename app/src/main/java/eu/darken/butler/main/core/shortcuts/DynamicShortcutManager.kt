package eu.darken.butler.main.core.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.main.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val shortcutRepo: ShortcutRepo,
    private val json: Json,
) {
    private val shortcutManager: ShortcutManager by lazy {
        context.getSystemService(ShortcutManager::class.java)
    }

    fun initialize() {
        log(TAG, INFO) { "Initializing ShortcutManagerService" }

        shortcutRepo.topShortcuts
            .distinctUntilChanged()
            .onEach { updateDynamicShortcuts(it) }
            .launchIn(appScope)
    }

    private fun updateDynamicShortcuts(shortcuts: List<RecentPath>) {
        try {
            // Always include "New Explorer" as the first shortcut
            val newExplorerShortcut = createNewExplorerShortcut()

            // Then add path shortcuts
            val pathShortcuts = shortcuts.mapIndexed { index, recentPath ->
                createPathShortcut(recentPath, index + 1)
            }

            val dynamicShortcuts = listOf(newExplorerShortcut) + pathShortcuts
            shortcutManager.dynamicShortcuts = dynamicShortcuts
            log(TAG, DEBUG) { "Updated ${dynamicShortcuts.size} dynamic shortcuts" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to update shortcuts: ${e.asLog()}" }
        }
    }

    private fun createNewExplorerShortcut(): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = EXPLORER_NEW_ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        return ShortcutInfo.Builder(context, NEW_EXPLORER_SHORTCUT_ID).apply {
            setShortLabel(context.getString(R.string.shortcut_explorer_new_short))
            setLongLabel(context.getString(R.string.shortcut_explorer_new_long))
            setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_folder_add))
            setIntent(intent)
            setRank(0)
        }.build()
    }

    private fun createPathShortcut(recentPath: RecentPath, rank: Int): ShortcutInfo {
        log(TAG, VERBOSE) { "Creating shortcut for rank $rank: $recentPath" }

        val label = recentPath.path.userReadablePath.get(context)
        val serializedPath = json.encodeToString(PolymorphicSerializer(APath::class), recentPath.path)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = EXPLORER_SHORTCUT_ACTION
            putExtra(EXPLORER_EXTRA_PATH, serializedPath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        return ShortcutInfo.Builder(context, recentPath.id.toString()).apply {
            setShortLabel(label)
            setLongLabel(recentPath.path.path)
            setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_folder))
            setIntent(intent)
            setRank(rank)
        }.build()
    }

    private fun reportShortcutUsed(shortcutId: String) {
        shortcutManager.reportShortcutUsed(shortcutId)
        log(TAG, DEBUG) { "Reported shortcut usage: $shortcutId" }
    }

    fun reportPathShortcutUsed(serializedPath: String) {
        // Try to find the shortcut by matching the serialized path in the intent extras
        val matchingShortcut = shortcutManager.dynamicShortcuts.find { shortcut ->
            shortcut.intent?.getStringExtra(EXPLORER_EXTRA_PATH) == serializedPath
        }

        matchingShortcut?.let {
            reportShortcutUsed(it.id)
            log(TAG, DEBUG) { "Found and reported path shortcut: ${it.id}" }
        } ?: run {
            log(TAG, WARN) { "No matching shortcut found for serialized path" }
        }
    }

    fun reportNewExplorerShortcutUsed() {
        log(TAG, VERBOSE) { "reportNewExplorerShortcutUsed()" }
        reportShortcutUsed(NEW_EXPLORER_SHORTCUT_ID)
    }

    suspend fun trackDirectoryAccess(path: APath<*>) {
        shortcutRepo.trackAccess(path)
    }

    companion object {
        private val TAG = logTag("Shortcuts", "Manager")
        const val EXPLORER_SHORTCUT_ACTION = "eu.darken.butler.EXPLORER_OPEN_PATH"
        const val EXPLORER_NEW_ACTION = "eu.darken.butler.EXPLORER_NEW"
        const val EXPLORER_EXTRA_PATH = "explorer_path"
        const val NEW_EXPLORER_SHORTCUT_ID = "explorer_new"
    }
}