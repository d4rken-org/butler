package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun PathCrumbBar(
    currentPath: String,
    onPathChanged: (String) -> Unit,
    onNavigateToPath: (String) -> Unit,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier,
    onValidationError: ((String) -> Unit)? = null
) {
    var isEditMode by remember { mutableStateOf(false) }
    var editTextValue by remember(currentPath) { 
        mutableStateOf(TextFieldValue(currentPath, TextRange(currentPath.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Parse path into segments using our new architecture
    val pathSegments = remember(currentPath) {
        PathUtils.parsePath(currentPath)
    }

    // Auto-scroll to the end when path changes
    LaunchedEffect(pathSegments.size) {
        if (pathSegments.isNotEmpty()) {
            listState.animateScrollToItem(pathSegments.size - 1)
        }
    }

    // Focus the text field and position cursor at end when entering edit mode
    LaunchedEffect(isEditMode) {
        if (isEditMode) {
            editTextValue = TextFieldValue(currentPath, TextRange(currentPath.length))
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 8.dp) // Increased vertical padding for consistent height
    ) {
        if (isEditMode) {
            // Edit mode - minimal text field with full absolute path
            BasicTextField(
                value = editTextValue,
                onValueChange = { editTextValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        
                        when (val result = PathUtils.validateAndNormalizePath(editTextValue.text, currentPath)) {
                            is PathValidationResult.Valid -> {
                                onNavigateToPath(result.normalizedPath)
                                isEditMode = false
                            }
                            
                            is PathValidationResult.NavigateToHome -> {
                                onNavigateToHome()
                                isEditMode = false
                            }
                            
                            is PathValidationResult.Invalid -> {
                                onValidationError?.invoke(result.error)
                                // Keep in edit mode, let user fix the error
                            }
                        }
                    }
                )
            )
        } else {
            // Display mode - show segments with appropriate icons/text
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(pathSegments) { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (segment) {
                            is PathSegment.Location -> {
                                // Location segments - icon only
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            when (segment.type) {
                                                LocationType.HOME -> onNavigateToHome()
                                                else -> onNavigateToPath(segment.rootPath)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp), // Same padding as text segments
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = segment.icon,
                                        contentDescription = segment.name,
                                        tint = if (segment.type == LocationType.HOME) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            is PathSegment.StorageRoot -> {
                                // Storage root segments - icon only
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            onNavigateToPath(segment.path)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp), // Same padding as text segments
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = segment.icon,
                                        contentDescription = segment.name,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            is PathSegment.Directory -> {
                                // Directory segments - clickable text
                                val isLast = pathSegments.lastOrNull() == segment
                                Text(
                                    text = segment.name,
                                    style = if (isLast) {
                                        MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    } else {
                                        MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            if (isLast) {
                                                // Clicked current segment - enter edit mode
                                                isEditMode = true
                                            } else {
                                                // Clicked previous segment - navigate
                                                onNavigateToPath(segment.path)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Separator (except for last item)
                        if (pathSegments.lastOrNull() != segment) {
                            Text(
                                text = "/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}