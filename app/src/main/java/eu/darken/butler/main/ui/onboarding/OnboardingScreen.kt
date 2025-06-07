package eu.darken.butler.main.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.uix.waitForState
import eu.darken.butler.main.ui.onboarding.OnboardingViewModel.State.*
import eu.darken.butler.main.ui.onboarding.pages.PrivacyPage
import eu.darken.butler.main.ui.onboarding.pages.WelcomePage
import kotlinx.coroutines.launch

@Preview2
@Composable
private fun OnboardingScreenPreview() {
    OnboardingScreen(
        state = OnboardingViewModel.State(Page.WELCOME),
        onComplete = {},
    )
}

@Composable
fun OnboardingScreenHost(
    vm: OnboardingViewModel = hiltViewModel()
) {
    val state by waitForState(vm.state)

    state?.let {
        OnboardingScreen(
            state = it,
            onComplete = vm::completeOnboarding,
        )
    }
}

@Composable
private fun OnboardingScreen(
    state: OnboardingViewModel.State,
    onComplete: () -> Unit,
) {

    val pagerState = rememberPagerState(
        initialPage = state.currentPage.ordinal,
        pageCount = { Page.entries.size }
    )
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (Page.entries[page]) {
                Page.WELCOME -> WelcomePage()
                Page.PRIVACY -> PrivacyPage(
                    onAccept = { onComplete() }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pagerState.currentPage > 0) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                ) {
                    Text("Back")
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pagerState.pageCount) { index ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(horizontal = 2.dp)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                },
                                shape = CircleShape
                            )
                    )
                }
            }

            if (pagerState.currentPage < pagerState.pageCount - 1) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                ) {
                    Text("Next")
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
        }
    }
}

