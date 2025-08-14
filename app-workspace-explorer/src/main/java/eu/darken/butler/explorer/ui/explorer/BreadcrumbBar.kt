package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ChevronRight
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.explorer.core.BreadcrumbGenerator
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation

@Composable
fun BreadcrumbBar(
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: ((String) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var isEditMode by remember { mutableStateOf(false) }
    var editTextValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Build current path from breadcrumbs
    val currentPath = remember(breadcrumbs) {
        when (val lastTarget = breadcrumbs.lastOrNull()?.target) {
            is ExplorerNavigation.Target.Directory -> lastTarget.path.path
            else -> "/"
        }
    }

    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty() && !isEditMode) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Enter edit mode with current path
    LaunchedEffect(isEditMode) {
        if (isEditMode) {
            editTextValue = TextFieldValue(currentPath, TextRange(currentPath.length))
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
            // Edit mode - show text field
            BasicTextField(
                value = editTextValue,
                onValueChange = { editTextValue = it },
                modifier = Modifier
                    .fillMaxWidth()
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
                        val pathToNavigate = editTextValue.text.trim()
                        if (pathToNavigate.isNotEmpty()) {
                            onNavigateToPath(pathToNavigate)
                        }
                        isEditMode = false
                    }
                )
            )
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
                            text = "Loading...",
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

                        Box(
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
                            contentAlignment = Alignment.Center
                        ) {
                            // Use icon from breadcrumb data, or show text if no icon or preferIcon is false
                            if (breadcrumb.icon != null && (breadcrumb.preferIcon || breadcrumb.label.get(context)
                                    .isEmpty())
                            ) {
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
                            } else {
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
            label = "storage".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage"))
        ),
        ExplorerBreadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated"))
        ),
        ExplorerBreadcrumb(
            label = "0".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated/0"))
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