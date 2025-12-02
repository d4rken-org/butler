package eu.darken.butler.explorer.core.engine

import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.PhoneAndroid
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeLocationLoader @Inject constructor(
    private val trashRepo: TrashRepo,
) {

    private val tag = logTag("Explorer", "HomeLocationLoader")

    private suspend fun checkLocationRequirements(): PathRequirements {
        log(tag) { "checkLocationRequirements(): Checking requirements for Home" }
        return PathRequirements()
    }

    fun loadHome(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadHome(): Loading home location" }

        val setupRequirements = checkLocationRequirements()
        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Home(
                setupRequirements = setupRequirements,
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_home_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        // Get trash stats for the subtitle
        val trashItems = trashRepo.getAllItems().first()
        val trashSize = trashItems.sumOf { it.size }
        val trashCount = trashItems.size

        val shortcuts = listOf(
            ExplorerItem.Shortcut(
                shortcutId = "device",
                displayIcon = Icons.TwoTone.PhoneAndroid,
                displayName = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device,
                subtitle = caString { "${Build.MODEL} (Android ${Build.VERSION.SDK_INT})" },
            ),
            ExplorerItem.Shortcut(
                shortcutId = "trash",
                displayIcon = Icons.TwoTone.Delete,
                displayName = R.string.explorer_navigation_trash.toCaString(),
                target = ExplorerNavigation.Target.Trash,
                subtitle = caString { cx ->
                    when {
                        trashCount == 0 -> cx.getString(R.string.explorer_trash_empty_state)
                        else -> {
                            val countText = cx.resources.getQuantityString(
                                R.plurals.explorer_trash_item_count,
                                trashCount,
                                trashCount,
                            )
                            val sizeText = formatFileSize(cx, trashSize)
                            "$countText • $sizeText"
                        }
                    }
                },
            ),
        )

        // Calculate device storage info
        val stat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: Exception) {
            log(tag, WARN) { "loadHome(): Failed to get storage info: ${e.message}" }
            null
        }

        val info = ExplorerLocation.Home.Info(
            shortcutCount = shortcuts.size,
            totalDeviceStorage = stat?.totalBytes,
            usedStorage = stat?.let { it.totalBytes - it.availableBytes },
        )

        log(tag, INFO) { "loadHome(): Created home with ${shortcuts.size} shortcuts" }

        context.updateState {
            copy(
                items = shortcuts,
                info = info,
                progress = null,
            )
        }
    }
}