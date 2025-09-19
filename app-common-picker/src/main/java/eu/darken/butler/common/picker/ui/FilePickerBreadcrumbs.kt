package eu.darken.butler.common.picker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath

@Composable
fun FilePickerBreadcrumbs(
    currentPath: APath?,
    onNavigate: (APath) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (currentPath == null) return
    
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when path changes
    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Root/Home button
        TextButton(
            onClick = { 
                val rootPath = LocalPath.build("/storage/emulated/0")
                onNavigate(rootPath)
            }
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Root",
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        
        // Path segments - simplified
        val pathStr = when (currentPath) {
            is LocalPath -> currentPath.path
            is RawPath -> currentPath.path
            else -> currentPath.toString()
        }
        
        val segments = pathStr.split("/").filter { it.isNotEmpty() }
        
        segments.forEachIndexed { index, segment ->
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            TextButton(
                onClick = {
                    val targetPath = LocalPath.build(
                        "/" + segments.take(index + 1).joinToString("/")
                    )
                    onNavigate(targetPath)
                }
            ) {
                Text(
                    text = segment,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}