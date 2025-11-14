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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.main.ui.onboarding.components.CardLayout
import eu.darken.butler.main.ui.onboarding.components.OnboardingInfoCard
import eu.darken.butler.main.ui.onboarding.components.OnboardingPageHeader
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label

@Composable
internal fun WorkspacesPage(
    onContinue: () -> Unit = {},
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }
    
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
                        Icon(
                            imageVector = Icons.TwoTone.Tab,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                    OnboardingInfoCard(
                        title = Workspace.Type.EXPLORER.label.asComposable(),
                        description = stringResource(R.string.onboarding_workspaces_explorer_description),
                        layout = CardLayout.Vertical,
                        fixedHeight = 140.dp,
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = Workspace.Type.EXPLORER.icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    OnboardingInfoCard(
                        title = Workspace.Type.SEARCHER.label.asComposable(),
                        description = stringResource(R.string.onboarding_workspaces_searcher_description),
                        layout = CardLayout.Vertical,
                        fixedHeight = 140.dp,
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = Workspace.Type.SEARCHER.icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OnboardingInfoCard(
                        title = Workspace.Type.EDITOR.label.asComposable(),
                        description = stringResource(R.string.onboarding_workspaces_editor_description),
                        layout = CardLayout.Vertical,
                        fixedHeight = 140.dp,
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = Workspace.Type.EDITOR.icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    OnboardingInfoCard(
                        title = Workspace.Type.APPS.label.asComposable(),
                        description = stringResource(R.string.onboarding_workspaces_apps_description),
                        layout = CardLayout.Vertical,
                        fixedHeight = 140.dp,
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = Workspace.Type.APPS.icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
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

@Preview2
@Composable
private fun WorkspacesPagePreview() {
    PreviewWrapper {
        WorkspacesPage()
    }
}