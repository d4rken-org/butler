package eu.darken.butler.main.ui.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.manager.rows.preview.WorkspaceMockPreview

private val PreviewBodyHeight = 84.dp

@Composable
fun OnboardingWorkspaceTabCard(
    modifier: Modifier = Modifier,
    type: Workspace.Type,
    description: String,
) {
    Column(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = type.icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = type.label.asComposable(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PreviewBodyHeight)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        // The mock filenames are decoration, not content TalkBack should read out
                        .clearAndSetSemantics {},
                ) {
                    // The mocks pin a 10.sp font but inherit a scalable lineHeight, so at large
                    // system font scales their rows overflow the fixed body height. The thumbnail
                    // is an illustration, so pin it to a fixed size instead.
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f),
                    ) {
                        WorkspaceMockPreview(type = type)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            modifier = Modifier.fillMaxWidth(),
            text = description,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingWorkspaceTabCardPreview() {
    OnboardingWorkspaceTabCard(
        modifier = Modifier.padding(16.dp),
        type = Workspace.Type.EXPLORER,
        description = "Browse and manage your files",
    )
}

@Composable
private fun OnboardingWorkspaceTabCardRowSample() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingWorkspaceTabCard(
            modifier = Modifier.weight(1f),
            type = Workspace.Type.EXPLORER,
            description = "Browse and manage your files",
        )
        OnboardingWorkspaceTabCard(
            modifier = Modifier.weight(1f),
            type = Workspace.Type.SEARCHER,
            description = "Find files anywhere on your device",
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingWorkspaceTabCardRowPreview() {
    OnboardingWorkspaceTabCardRowSample()
}

@Preview(showBackground = true, name = "Row - Narrow", widthDp = 320)
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingWorkspaceTabCardRowNarrowPreview() {
    OnboardingWorkspaceTabCardRowSample()
}

@Preview(showBackground = true, name = "Row - Large font", fontScale = 1.5f)
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingWorkspaceTabCardRowLargeFontPreview() {
    OnboardingWorkspaceTabCardRowSample()
}

@Preview(showBackground = true, name = "Row - RTL", locale = "ar")
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingWorkspaceTabCardRowRtlPreview() {
    OnboardingWorkspaceTabCardRowSample()
}
