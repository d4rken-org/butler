package eu.darken.butler.main.ui.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.rows.PaneBadge
import eu.darken.butler.workspace.ui.manager.rows.preview.WorkspaceMockPreview

@Composable
fun OnboardingMultiPaneMockup(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // OnboardingContentWrapper allows 600dp on medium windows, and a 600dp-wide 16:9 frame
            // is taller than the scroll viewport left in phone landscape. widthIn has to come
            // before fillMaxWidth - fixed incoming constraints would swallow the cap.
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
            // Decorative illustration - without this TalkBack reads out every mock filename
            .clearAndSetSemantics {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp)),
        ) {
            MockRail()

            MockPane(
                modifier = Modifier.weight(1f),
                type = Workspace.Type.EXPLORER,
                paneNumber = 0,
            )

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            MockPane(
                modifier = Modifier.weight(1f),
                type = Workspace.Type.SEARCHER,
                paneNumber = 1,
            )
        }
    }
}

@Composable
private fun MockRail(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(22.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MockRailItem(type = Workspace.Type.EXPLORER, isActive = true)
        MockRailItem(type = Workspace.Type.SEARCHER, isActive = true)
        MockRailItem(type = Workspace.Type.EDITOR, isActive = false)
    }
}

@Composable
private fun MockRailItem(
    modifier: Modifier = Modifier,
    type: Workspace.Type,
    isActive: Boolean,
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = type.icon,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = if (isActive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
    }
}

@Composable
private fun MockPane(
    modifier: Modifier = Modifier,
    type: Workspace.Type,
    paneNumber: Int,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Same reason as the tab card: the mock rows must not grow with the system font scale
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f),
        ) {
            WorkspaceMockPreview(type = type)
        }

        PaneBadge(
            paneNumber = paneNumber,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingMultiPaneMockupPreview() {
    OnboardingMultiPaneMockup(modifier = Modifier.padding(16.dp))
}

@Preview(showBackground = true, name = "Narrow", widthDp = 320)
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingMultiPaneMockupNarrowPreview() {
    OnboardingMultiPaneMockup(modifier = Modifier.padding(16.dp))
}

@Preview(showBackground = true, name = "RTL", locale = "ar")
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingMultiPaneMockupRtlPreview() {
    OnboardingMultiPaneMockup(modifier = Modifier.padding(16.dp))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MockRailPreview() {
    MockRail(modifier = Modifier.padding(16.dp))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MockRailItemPreview() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MockRailItem(type = Workspace.Type.EXPLORER, isActive = true)
        MockRailItem(type = Workspace.Type.EDITOR, isActive = false)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MockPanePreview() {
    MockPane(
        modifier = Modifier
            .padding(16.dp)
            .width(120.dp)
            .aspectRatio(1f),
        type = Workspace.Type.EXPLORER,
        paneNumber = 0,
    )
}
