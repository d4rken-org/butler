package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.explorer.core.engine.ExplorerLocation

@Composable
fun BreadcrumbBar(
    breadcrumbs: List<ExplorerLocation.Breadcrumb>,
    onBreadcrumbClick: (ExplorerLocation.Breadcrumb.Target) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
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
                        .clickable(enabled = !isLast) {
                            if (!isLast) {
                                onBreadcrumbClick(breadcrumb.target)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Use icon from breadcrumb data, or show text if no icon or preferIcon is false
                    if (breadcrumb.icon != null && (breadcrumb.preferIcon || breadcrumb.label.get(context).isEmpty())) {
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
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
fun BreadcrumbBarPreview() {
    val breadcrumbs = listOf(
        ExplorerLocation.Home.CRUMB,
        ExplorerLocation.Device.CRUMB,
        ExplorerLocation.Breadcrumb(
            label = "storage".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage"))
        ),
        ExplorerLocation.Breadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage/emulated"))
        ),
        ExplorerLocation.Breadcrumb(
            label = "0".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage/emulated/0"))
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
    val breadcrumbs = listOf(
        ExplorerLocation.Home.CRUMB
    )

    PreviewWrapper {
        BreadcrumbBar(
            breadcrumbs = breadcrumbs,
            onBreadcrumbClick = {}
        )
    }
}