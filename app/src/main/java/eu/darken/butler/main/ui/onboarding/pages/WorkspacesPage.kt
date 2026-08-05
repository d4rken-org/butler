package eu.darken.butler.main.ui.onboarding.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.main.ui.onboarding.components.OnboardingContentWrapper
import eu.darken.butler.main.ui.onboarding.components.OnboardingMultiPaneMockup
import eu.darken.butler.main.ui.onboarding.components.OnboardingPageHeader
import eu.darken.butler.main.ui.onboarding.components.OnboardingWorkspaceTabCard
import eu.darken.butler.workspace.core.Workspace

@Composable
internal fun WorkspacesPage(
    onContinue: () -> Unit = {},
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    OnboardingContentWrapper {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(
                animationSpec = tween(400)
            ) + slideInVertically(
                initialOffsetY = { 30 },
                animationSpec = tween(400)
            ),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingPageHeader(
                    title = stringResource(R.string.onboarding_workspaces_title),
                    message = stringResource(R.string.onboarding_workspaces_message),
                    subtitleAlpha = 0.8f,
                    icon = {
                        OnboardingMultiPaneMockup()
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OnboardingWorkspaceTabCard(
                            modifier = Modifier.weight(1f),
                            type = Workspace.Type.EXPLORER,
                            description = stringResource(R.string.onboarding_workspaces_explorer_description),
                        )
                        OnboardingWorkspaceTabCard(
                            modifier = Modifier.weight(1f),
                            type = Workspace.Type.SEARCHER,
                            description = stringResource(R.string.onboarding_workspaces_searcher_description),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OnboardingWorkspaceTabCard(
                            modifier = Modifier.weight(1f),
                            type = Workspace.Type.EDITOR,
                            description = stringResource(R.string.onboarding_workspaces_editor_description),
                        )
                        OnboardingWorkspaceTabCard(
                            modifier = Modifier.weight(1f),
                            type = Workspace.Type.APPS,
                            description = stringResource(R.string.onboarding_workspaces_apps_description),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_workspaces_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 400,
                    delayMillis = 200
                )
            )
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.onboarding_workspaces_action))
            }
        }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacesPagePreview() {
    WorkspacesPage()
}

@Preview(showBackground = true, name = "Narrow", widthDp = 320)
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacesPageNarrowPreview() {
    WorkspacesPage()
}

@Preview(showBackground = true, name = "Large font", fontScale = 1.5f)
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacesPageLargeFontPreview() {
    WorkspacesPage()
}

@Preview(showBackground = true, name = "RTL", locale = "ar")
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacesPageRtlPreview() {
    WorkspacesPage()
}