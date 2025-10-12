package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.BreadcrumbGenerator
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import java.io.File

@Composable
fun BreadcrumbBar(
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: ((String) -> Unit)? = null,
    safLocationManager: SAFLocationManager? = null,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var isEditMode by remember { mutableStateOf(false) }
    var editTextValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Detect current path type and extract relevant information
    data class PathInfo(
        val displayPath: String,
        val path: APath?,
        val prefixIcon: ImageVector? = null,
        val prefixLabel: String? = null,
    )

    val pathInfo = remember(breadcrumbs, safLocationManager) {
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
                        val locationName = safLocationManager?.findPermissionFor(path)?.location?.displayName?.get(context)

                        PathInfo(
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

                        PathInfo(
                            displayPath = pathAfterRoot, // Everything after the first "/"
                            path = path,
                            prefixIcon = rootBreadcrumb?.icon,
                            prefixLabel = rootBreadcrumb?.label?.get(context),
                        )
                    }
                    else -> PathInfo(displayPath = "", path = null)
                }
            }
            else -> PathInfo(displayPath = "", path = null)
        }
    }

    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty() && !isEditMode) {
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

    // Enter edit mode with current path
    LaunchedEffect(isEditMode, pathInfo) {
        if (isEditMode) {
            editTextValue = TextFieldValue(pathInfo.displayPath, TextRange(pathInfo.displayPath.length))
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (isEditMode && onNavigateToPath != null) {
            // Edit mode - unified UI for both SAF and Local paths
            if (pathInfo.prefixIcon != null && pathInfo.prefixLabel != null) {
                // Show icon + label prefix + editable suffix
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Non-editable clickable prefix showing root location
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
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
                                    else -> {
                                        // Fallback: just exit edit mode
                                        isEditMode = false
                                    }
                                }
                            }
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = File.separator,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Editable suffix (path after root)
                    BasicTextField(
                        value = editTextValue,
                        onValueChange = { editTextValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.key == Key.Escape) {
                                    keyboardController?.hide()
                                    isEditMode = false
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                val editedPath = editTextValue.text.trim()

                                // Handle navigation based on path type
                                when (val path = pathInfo.path) {
                                    is SAFPath -> {
                                        // Reconstruct SAFPath from tree root + edited segments
                                        val segments = if (editedPath.isEmpty() || editedPath == "/") {
                                            emptyArray()
                                        } else {
                                            editedPath.split("/").filter { it.isNotEmpty() }.toTypedArray()
                                        }
                                        val newSafPath = SAFPath.build(path.treeRootUri, *segments)
                                        onBreadcrumbClick(ExplorerNavigation.Target.Directory(newSafPath))
                                    }
                                    is LocalPath -> {
                                        // Reconstruct full path with leading "/"
                                        val fullPath = "/$editedPath"
                                        onNavigateToPath(fullPath)
                                    }
                                    else -> {
                                        // Fallback for other path types or null
                                    }
                                }

                                isEditMode = false
                            }
                        )
                    )
                }
            }
        } else {
            // Display mode - show breadcrumbs or loading state
            if (breadcrumbs.isEmpty()) {
                // Show loading state when breadcrumbs are empty
                // Match the height of regular breadcrumbs (icon 20.dp + padding 8.dp vertical)
                Box(
                    modifier = Modifier
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Show actual breadcrumbs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { index, breadcrumb ->
                        val isLast = index == breadcrumbs.lastIndex

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    when {
                                        // Only allow edit mode for actual directory paths, not Home/Device
                                        isLast && onNavigateToPath != null &&
                                            breadcrumb.target is ExplorerNavigation.Target.Directory -> {
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
                                }
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Show icon if showIcon is true and icon exists
                            if (breadcrumb.showIcon && breadcrumb.icon != null) {
                                Icon(
                                    imageVector = breadcrumb.icon,
                                    contentDescription = breadcrumb.label.get(context),
                                    tint = if (isLast) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Show text if showText is true
                            if (breadcrumb.showText) {
                                Text(
                                    text = breadcrumb.label.get(context),
                                    style = if (isLast) {
                                        MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    } else {
                                        MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                        }

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
    }
}

@Preview2
@Composable
fun BreadcrumbBarPreview() {
    val breadcrumbs = listOf(
        BreadcrumbGenerator.HOME,
        BreadcrumbGenerator.DEVICE,
        ExplorerBreadcrumb(
            label = "/".toCaString(),
            icon = Icons.TwoTone.FolderOpen,
            showIcon = true,
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/"))
        ),
        ExplorerBreadcrumb(
            label = "storage".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage"))
        ),
        ExplorerBreadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated"))
        ),
        ExplorerBreadcrumb(
            label = "0".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
        )
    )

    PreviewWrapper {
        BreadcrumbBar(
            breadcrumbs = breadcrumbs,
            onBreadcrumbClick = {}
        )
    }
}

@Preview2
@Composable
fun BreadcrumbBarHomeOnlyPreview() {
    val breadcrumbs = listOf(BreadcrumbGenerator.HOME)

    PreviewWrapper {
        BreadcrumbBar(
            breadcrumbs = breadcrumbs,
            onBreadcrumbClick = {}
        )
    }
}

@Preview2
@Composable
fun BreadcrumbBarEmptyPreview() {
    PreviewWrapper {
        BreadcrumbBar(
            breadcrumbs = emptyList(),
            onBreadcrumbClick = {}
        )
    }
}