package eu.darken.butler.main.ui.onboarding.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Rocket
import androidx.compose.material.icons.twotone.Shield
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.main.ui.onboarding.components.OnboardingInfoCard
import eu.darken.butler.main.ui.onboarding.components.OnboardingPageHeader

@Composable
internal fun SustainabilityPage(
    onContinue: () -> Unit = {},
    initiallyVisible: Boolean = false,
) {
    var isVisible by remember { mutableStateOf(initiallyVisible) }

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
                    title = stringResource(R.string.onboarding_sustainability_title),
                    message = stringResource(R.string.onboarding_sustainability_message),
                    icon = {
                        Icon(
                            imageVector = Icons.TwoTone.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OnboardingInfoCard(
                        title = stringResource(R.string.onboarding_sustainability_free_title),
                        description = stringResource(R.string.onboarding_sustainability_free_description),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    )

                    OnboardingInfoCard(
                        title = stringResource(R.string.onboarding_sustainability_pro_title),
                        description = stringResource(R.string.onboarding_sustainability_pro_description),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = {
                            Icon(
                                imageVector = Icons.TwoTone.Rocket,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                        }
                    )

                    OnboardingInfoCard(
                        title = stringResource(R.string.onboarding_sustainability_support_title),
                        description = stringResource(R.string.onboarding_sustainability_support_description),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = {
                            Icon(
                                imageVector = Icons.TwoTone.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                        }
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
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.onboarding_sustainability_action))
            }
        }
    }
}

@Preview2
@Composable
private fun SustainabilityPagePreview() {
    PreviewWrapper {
        SustainabilityPage(
            initiallyVisible = true
        )
    }
}