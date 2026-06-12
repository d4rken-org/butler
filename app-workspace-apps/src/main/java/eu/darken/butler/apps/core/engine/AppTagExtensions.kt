package eu.darken.butler.apps.core.engine

import androidx.annotation.StringRes
import eu.darken.butler.apps.R
import eu.darken.butler.workspace.contracts.apps.AppTag

/**
 * UI-related extension properties for [AppTag].
 * The core AppTag class is in app-workspace for serialization purposes,
 * while these display properties are provided here in app-workspace-apps.
 */

/**
 * String resource ID for the tag's display label.
 */
val AppTag.labelRes: Int
    @StringRes get() = when (this) {
        is AppTag.Disabled -> R.string.apps_tag_disabled_label
        is AppTag.System -> R.string.apps_tag_system_label
        is AppTag.Sideloaded -> R.string.apps_tag_sideloaded_label
        is AppTag.UpdatedSystem -> R.string.apps_tag_updated_label
        is AppTag.Debug -> R.string.apps_tag_debug_label
        is AppTag.SplitApk -> R.string.apps_tag_split_label
        is AppTag.User -> R.string.apps_tag_user_label
        is AppTag.Enabled -> R.string.apps_filter_tag_enabled_label
        is AppTag.UserApp -> R.string.apps_filter_tag_user_app_label
    }

/**
 * Priority for sorting tags in the UI (lower = more important).
 */
val AppTag.priority: Int
    get() = when (this) {
        is AppTag.User -> 1
        is AppTag.Disabled -> 5
        is AppTag.Enabled -> 6
        is AppTag.System -> 10
        is AppTag.UserApp -> 11
        is AppTag.Sideloaded -> 15
        is AppTag.UpdatedSystem -> 20
        is AppTag.Debug -> 25
        is AppTag.SplitApk -> 30
    }

/**
 * All standard tags for the filter dialog (excludes dynamic User profile tags).
 */
val AppTag.Companion.standardTags: List<AppTag>
    get() = listOf(
        AppTag.System,
        AppTag.UserApp,
        AppTag.Enabled,
        AppTag.Disabled,
        AppTag.Sideloaded,
        AppTag.UpdatedSystem,
        AppTag.Debug,
        AppTag.SplitApk,
    )

/**
 * Returns the conflicting tag that should be auto-removed when this tag is selected.
 * Used for mutual exclusivity in filter UI (e.g., System ↔ UserApp, Disabled ↔ Enabled).
 */
val AppTag.conflictingTag: AppTag?
    get() = when (this) {
        AppTag.System -> AppTag.UserApp
        AppTag.UserApp -> AppTag.System
        AppTag.Disabled -> AppTag.Enabled
        AppTag.Enabled -> AppTag.Disabled
        else -> null
    }
