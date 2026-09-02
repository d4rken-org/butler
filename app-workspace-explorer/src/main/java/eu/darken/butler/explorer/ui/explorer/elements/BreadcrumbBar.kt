package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ChevronRight
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.Lan
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.BadgedIcon
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.ui.pagerFriendlyHorizontalScroll
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.dnd.dropZone
import eu.darken.butler.workspace.ui.modal.DismissWhenPaneUnfocused
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

@Composable
fun BreadcrumbBar(
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: ((APath<*>) -> Unit)? = null,
    onCommitEditedPath: ((APath<*>, String) -> Unit)? = null,
    onSetAsHome: ((ExplorerNavigation.Target) -> Unit)? = null,
    onCopyPath: ((String) -> Unit)? = null,
    safLocationManager: SAFLocationManager? = null,
    showBackground: Boolean = true,
    cutoutWidth: Dp = 0.dp,
) {
    val scrollState = rememberScrollState()
    var isEditMode by remember { mutableStateOf(false) }
    var editTextValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    val requestWorkspaceFocus = LocalWorkspaceFocusRequest.current
    var hadFocusWhileEditing by remember { mutableStateOf(false) }
    var showContextMenuForIndex by remember { mutableStateOf<Int?>(null) }

    // Animation state for "Set as home" feedback - tracks which breadcrumb to animate
    var animatingBreadcrumbIndex by remember { mutableStateOf<Int?>(null) }

    // Clear animation after delay
    LaunchedEffect(animatingBreadcrumbIndex) {
        if (animatingBreadcrumbIndex != null) {
            delay(400)
            animatingBreadcrumbIndex = null
        }
    }

    val pathInfo = rememberBreadcrumbPathInfo(breadcrumbs, safLocationManager)

    LaunchedEffect(breadcrumbs) {
        if (breadcrumbs.isNotEmpty() && !isEditMode) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Reset edit mode when breadcrumbs change (new directory loaded)
    LaunchedEffect(breadcrumbs) {
        if (isEditMode) {
            isEditMode = false
            keyboardController?.hide()
        }
    }

    // Handle edit mode focus - track whether we had focus to distinguish "waiting for focus" from "lost focus"
    LaunchedEffect(isEditMode, pathInfo, isWorkspaceFocused) {
        when {
            isEditMode && isWorkspaceFocused -> {
                // Enter/stay in edit mode
                editTextValue = TextFieldValue(pathInfo.displayPath, TextRange(pathInfo.displayPath.length))
                focusRequester.requestFocus()
                hadFocusWhileEditing = true
            }
            isEditMode && !isWorkspaceFocused && hadFocusWhileEditing -> {
                // Lost focus after having it - user clicked away, exit edit mode
                isEditMode = false
                keyboardController?.hide()
            }
            // isEditMode && !isWorkspaceFocused && !hadFocusWhileEditing -> waiting for workspace focus
            !isEditMode -> {
                hadFocusWhileEditing = false
            }
        }
    }

    WorkspaceBackHandler(enabled = isEditMode) {
        isEditMode = false
        keyboardController?.hide()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showBackground) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.TopStart
    ) {
        when {
            isEditMode && onCommitEditedPath != null -> BreadcrumbEditRow(
                pathInfo = pathInfo,
                textValue = editTextValue,
                onTextValueChange = { editTextValue = it },
                focusRequester = focusRequester,
                onPrefixClick = {
                    requestWorkspaceFocus?.invoke()
                    // Navigate to root when clicking prefix
                    when (val path = pathInfo.path) {
                        is SAFPath -> {
                            // Navigate to SAF root
                            val rootPath = SAFPath.build(path.treeRootUri)
                            onBreadcrumbClick(ExplorerNavigation.Target.Directory(rootPath))
                            isEditMode = false
                        }
                        is LocalPath -> {
                            // Navigate to filesystem root
                            onBreadcrumbClick(ExplorerNavigation.Target.Directory(LocalPath.build("/")))
                            isEditMode = false
                        }
                        is ArchivePath -> {
                            // Navigate to the archive's root
                            onBreadcrumbClick(ExplorerNavigation.Target.Directory(ArchivePath.root(path.container)))
                            isEditMode = false
                        }
                        else -> {
                            // Fallback: just exit edit mode
                            isEditMode = false
                        }
                    }
                },
                onEscape = {
                    keyboardController?.hide()
                    isEditMode = false
                },
                onCommit = {
                    keyboardController?.hide()
                    pathInfo.path?.let { onCommitEditedPath(it, editTextValue.text) }
                    isEditMode = false
                },
            )
            breadcrumbs.isEmpty() -> BreadcrumbLoadingRow()
            else -> BreadcrumbDisplayRow(
                breadcrumbs = breadcrumbs,
                scrollState = scrollState,
                isWorkspaceFocused = isWorkspaceFocused,
                cutoutWidth = cutoutWidth,
                animatingBreadcrumbIndex = animatingBreadcrumbIndex,
                showContextMenuForIndex = showContextMenuForIndex,
                onShowContextMenu = { showContextMenuForIndex = it },
                onChipClick = { index, breadcrumb ->
                    requestWorkspaceFocus?.invoke()
                    val isLast = index == breadcrumbs.lastIndex
                    val isDirectory = breadcrumb.target is ExplorerNavigation.Target.Directory
                    when {
                        // Only allow edit mode for actual directory paths, not Home/Device
                        isLast && onNavigateToPath != null && isDirectory -> {
                            // Click on last breadcrumb that is a directory enters edit mode
                            isEditMode = true
                        }
                        !isLast -> {
                            // Click on non-last breadcrumbs always navigates
                            onBreadcrumbClick(breadcrumb.target)
                        }
                        // For Home/Device when last, clicking does nothing
                        // (could optionally refresh by calling onBreadcrumbClick)
                    }
                },
                onSetAsHome = onSetAsHome?.let { setAsHome ->
                    { index, target ->
                        animatingBreadcrumbIndex = index
                        setAsHome(target)
                    }
                },
                onCopyPath = onCopyPath,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BreadcrumbBarPreview() {
    BreadcrumbBar(
        breadcrumbs = MockDataProvider.createStorageBreadcrumbs(),
        onBreadcrumbClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BreadcrumbBarHomeOnlyPreview() {
    BreadcrumbBar(
        breadcrumbs = listOf(MockDataProvider.createHomeBreadcrumb()),
        onBreadcrumbClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BreadcrumbBarEmptyPreview() {
    BreadcrumbBar(
        breadcrumbs = emptyList(),
        onBreadcrumbClick = {},
    )
}

private data class BreadcrumbPathInfo(
    val displayPath: String,
    val path: APath<*>?,
    val prefixIcon: ImageVector? = null,
    val prefixLabel: String? = null,
)

/**
 * Detect the current path type and extract display path plus root prefix info.
 */
@Composable
private fun rememberBreadcrumbPathInfo(
    breadcrumbs: List<ExplorerBreadcrumb>,
    safLocationManager: SAFLocationManager?,
): BreadcrumbPathInfo {
    val context = LocalContext.current
    return remember(breadcrumbs, safLocationManager) {
        when (val lastTarget = breadcrumbs.lastOrNull()?.target) {
            is ExplorerNavigation.Target.Directory -> {
                when (val path = lastTarget.path) {
                    is SAFPath -> {
                        // For SAF paths, show only the relative segments
                        val segmentsPath = path.segments.joinToString("/")

                        // Find the SAF root breadcrumb by matching the tree root path
                        val safRootPath = SAFPath.build(path.treeRootUri)
                        val rootBreadcrumb = breadcrumbs.find {
                            it.target is ExplorerNavigation.Target.Directory &&
                                it.target.path == safRootPath
                        }
                        val locationName =
                            safLocationManager?.findPermissionFor(path)?.location?.displayName?.get(context)

                        BreadcrumbPathInfo(
                            displayPath = segmentsPath, // Empty when at SAF root
                            path = path,
                            prefixIcon = rootBreadcrumb?.icon,
                            prefixLabel = locationName,
                        )
                    }
                    is LocalPath -> {
                        // For local paths, split the leading "/" from the rest
                        val pathAfterRoot = path.path.removePrefix("/")

                        // Find the "/" root breadcrumb
                        val rootBreadcrumb = breadcrumbs.find {
                            it.target is ExplorerNavigation.Target.Directory &&
                                it.target.path is LocalPath &&
                                it.target.path.path == "/"
                        }

                        BreadcrumbPathInfo(
                            displayPath = pathAfterRoot, // Everything after the first "/"
                            path = path,
                            prefixIcon = rootBreadcrumb?.icon,
                            prefixLabel = rootBreadcrumb?.label?.get(context),
                        )
                    }
                    is ArchivePath -> BreadcrumbPathInfo(
                        // Edited text is interpreted as a path INSIDE the archive.
                        displayPath = path.segments.joinToString("/"),
                        path = path,
                        prefixIcon = Icons.TwoTone.FolderZip,
                        prefixLabel = path.container.name,
                    )

                    is SmbPath -> {
                        // The location root breadcrumb carries the label, edited text is relative to it.
                        val rootBreadcrumb = breadcrumbs.find {
                            it.target is ExplorerNavigation.Target.Directory &&
                                it.target.path == SmbPath.root(path.locationId)
                        }
                        BreadcrumbPathInfo(
                            displayPath = path.segments.joinToString("/"),
                            path = path,
                            prefixIcon = rootBreadcrumb?.icon ?: Icons.TwoTone.Lan,
                            prefixLabel = rootBreadcrumb?.label?.get(context),
                        )
                    }
                }
            }
            else -> BreadcrumbPathInfo(displayPath = "", path = null)
        }
    }
}

/**
 * Edit mode - unified UI for both SAF and Local paths.
 * Renders nothing when the path has no resolvable root prefix.
 */
@Composable
private fun BreadcrumbEditRow(
    modifier: Modifier = Modifier,
    pathInfo: BreadcrumbPathInfo,
    textValue: TextFieldValue,
    onTextValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onPrefixClick: () -> Unit,
    onEscape: () -> Unit,
    onCommit: () -> Unit,
) {
    if (pathInfo.prefixIcon == null || pathInfo.prefixLabel == null) return

    // Show icon + label prefix + editable suffix
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Non-editable clickable prefix showing root location
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onPrefixClick() }
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = pathInfo.prefixIcon,
                contentDescription = pathInfo.prefixLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = pathInfo.prefixLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = File.separator,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Editable suffix (path after root)
        BasicTextField(
            value = textValue,
            onValueChange = onTextValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape) {
                        onEscape()
                        true
                    } else {
                        false
                    }
                },
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { onCommit() }
            )
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BreadcrumbEditRowPreview() {
    BreadcrumbEditRow(
        pathInfo = BreadcrumbPathInfo(
            displayPath = "storage/emulated/0/Documents",
            path = LocalPath.build("/storage/emulated/0/Documents"),
            prefixIcon = Icons.TwoTone.Home,
            prefixLabel = "/",
        ),
        textValue = TextFieldValue("storage/emulated/0/Documents"),
        onTextValueChange = {},
        focusRequester = FocusRequester(),
        onPrefixClick = {},
        onEscape = {},
        onCommit = {},
    )
}

/**
 * Loading state shown while breadcrumbs are empty.
 * Matches the height of regular breadcrumbs (icon 20.dp + padding 8.dp vertical).
 */
@Composable
private fun BreadcrumbLoadingRow(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.explorer_loading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Display mode - the scrollable breadcrumb trail.
 *
 * [showContextMenuForIndex] and [animatingBreadcrumbIndex] are single shared values owned by
 * [BreadcrumbBar] so that at most one context menu is open and one chip animates at a time —
 * do not localize them into per-chip state.
 */
@Composable
private fun BreadcrumbDisplayRow(
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    scrollState: ScrollState,
    isWorkspaceFocused: Boolean,
    cutoutWidth: Dp,
    animatingBreadcrumbIndex: Int?,
    showContextMenuForIndex: Int?,
    onShowContextMenu: (Int?) -> Unit,
    onChipClick: (Int, ExplorerBreadcrumb) -> Unit,
    onSetAsHome: ((Int, ExplorerNavigation.Target) -> Unit)?,
    onCopyPath: ((String) -> Unit)?,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pagerFriendlyHorizontalScroll(scrollState, isWorkspaceFocused = isWorkspaceFocused)
            .padding(end = cutoutWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            val isLast = index == breadcrumbs.lastIndex
            val isDirectory = breadcrumb.target is ExplorerNavigation.Target.Directory
            val supportsContextMenu = when (breadcrumb.target) {
                is ExplorerNavigation.Target.Home,
                is ExplorerNavigation.Target.Device,
                is ExplorerNavigation.Target.Directory -> true
                else -> false // Trash doesn't support context menu
            }

            // Wrap breadcrumb + chevron in a Row for vertical alignment
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreadcrumbChip(
                    breadcrumb = breadcrumb,
                    isLast = isLast,
                    isAnimating = animatingBreadcrumbIndex == index,
                    showContextMenu = showContextMenuForIndex == index && supportsContextMenu,
                    onClick = { onChipClick(index, breadcrumb) },
                    onLongClick = {
                        // Show context menu for Home, Device, and Directory targets (not Trash)
                        if (supportsContextMenu) {
                            onShowContextMenu(index)
                        }
                    },
                    onDismissContextMenu = { onShowContextMenu(null) },
                    onSetAsHome = onSetAsHome?.let { setAsHome ->
                        {
                            onShowContextMenu(null)
                            setAsHome(index, breadcrumb.target as ExplorerNavigation.Target)
                        }
                    },
                    onCopyPath = if (isDirectory && onCopyPath != null) {
                        {
                            onShowContextMenu(null)
                            onCopyPath(breadcrumb.target.path.path)
                        }
                    } else null,
                )

                if (!isLast) {
                    Icon(
                        imageVector = Icons.TwoTone.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single clickable breadcrumb with "Set as home" animation feedback and an optional context menu.
 */
@Composable
private fun BreadcrumbChip(
    modifier: Modifier = Modifier,
    breadcrumb: ExplorerBreadcrumb,
    isLast: Boolean,
    isAnimating: Boolean,
    showContextMenu: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onSetAsHome: (() -> Unit)?,
    onCopyPath: (() -> Unit)?,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.dropZone(
            key = "crumb:${breadcrumb.target}",
            // Home, Device and Trash crumbs are no folder, so they register nothing and a drop on
            // them resolves to nothing rather than falling through to the listing.
            destination = (breadcrumb.target as? ExplorerNavigation.Target.Directory)
                ?.path
                ?.takeUnless { it is ArchivePath },
            allowOutsideContentBand = true,
        ),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val animatedScale by animateFloatAsState(
                targetValue = if (isAnimating) 1.3f else 1f,
                animationSpec = tween(durationMillis = 200),
                label = "breadcrumbScale",
            )
            val animatedColor = when {
                isAnimating -> MaterialTheme.colorScheme.primary
                isLast -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            // Always show icon if available
            BadgedIcon(
                modifier = Modifier.scale(animatedScale),
                icon = breadcrumb.icon,
                badge = breadcrumb.badgeIcon,
                iconSize = 16.dp,
                badgeSize = 10.dp,
                iconTint = animatedColor,
                badgeTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = breadcrumb.label.get(context),
                style = MaterialTheme.typography.labelMedium.copy(color = animatedColor),
            )
        }

        if (showContextMenu) {
            BreadcrumbContextMenu(
                onDismiss = onDismissContextMenu,
                onSetAsHome = onSetAsHome,
                onCopyPath = onCopyPath,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BreadcrumbChipPreview() {
    Row {
        BreadcrumbChip(
            breadcrumb = MockDataProvider.createHomeBreadcrumb(),
            isLast = false,
            isAnimating = false,
            showContextMenu = false,
            onClick = {},
            onLongClick = {},
            onDismissContextMenu = {},
            onSetAsHome = null,
            onCopyPath = null,
        )
        BreadcrumbChip(
            breadcrumb = MockDataProvider.createHomeBreadcrumb(),
            isLast = true,
            isAnimating = true,
            showContextMenu = false,
            onClick = {},
            onLongClick = {},
            onDismissContextMenu = {},
            onSetAsHome = null,
            onCopyPath = null,
        )
    }
}

/**
 * Context menu for Home, Device, and Directory breadcrumbs.
 * A null action callback hides the corresponding menu item.
 */
@Composable
private fun BreadcrumbContextMenu(
    onDismiss: () -> Unit,
    onSetAsHome: (() -> Unit)?,
    onCopyPath: (() -> Unit)?,
) {
    DismissWhenPaneUnfocused(expanded = true, onDismiss = onDismiss)
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        // "Set as home" available for all supported targets
        if (onSetAsHome != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explorer_breadcrumb_set_as_home_action)) },
                onClick = onSetAsHome,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Home,
                        contentDescription = null,
                    )
                },
            )
        }
        // "Copy path" only available for Directory targets
        if (onCopyPath != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explorer_breadcrumb_copy_path_action)) },
                onClick = onCopyPath,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.ContentCopy,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}
