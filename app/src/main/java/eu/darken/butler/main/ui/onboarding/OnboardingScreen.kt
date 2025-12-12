package eu.darken.butler.main.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.onboarding.OnboardingViewModel.State.*
import eu.darken.butler.main.ui.onboarding.pages.BetaPage
import eu.darken.butler.main.ui.onboarding.pages.PrivacyPage
import eu.darken.butler.main.ui.onboarding.pages.WelcomePage
import eu.darken.butler.main.ui.onboarding.pages.WorkspacesPage
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreenHost(vm: OnboardingViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        OnboardingScreen(
            state = state,
            onUpdateCheckChange = { vm.setUpdateCheckEnabled(it) },
            onMotdCheckChange = { vm.setMotdCheckEnabled(it) },
            onReadPrivacyPolicy = { vm.readPrivacyPolicy() },
            onFinishOnboarding = vm::completeOnboarding,
        )
    }
}

@Composable
private fun OnboardingScreen(
    state: OnboardingViewModel.State,
    onUpdateCheckChange: (Boolean) -> Unit,
    onMotdCheckChange: (Boolean) -> Unit,
    onReadPrivacyPolicy: () -> Unit,
    onFinishOnboarding: () -> Unit,
) {

    val pagerState =
        rememberPagerState(
            initialPage = state.startPage.ordinal,
            pageCount = { Page.entries.size }
        )
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .navigationBarsPadding()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false
        ) { page ->
            when (Page.entries[page]) {
                Page.WELCOME ->
                    WelcomePage(
                        onContinue = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    )

                Page.WORKSPACES ->
                    WorkspacesPage(
                        onContinue = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    )

                Page.BETA ->
                    BetaPage(
                        onContinue = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    )

                Page.PRIVACY ->
                    PrivacyPage(
                        isUpdateCheckEnabled = state.isUpdateCheckEnabled,
                        onUpdateCheckChange = onUpdateCheckChange,
                        isMotdCheckEnabled = state.isMotdCheckEnabled,
                        onMotdCheckChange = onMotdCheckChange,
                        onReadPrivacyPolicy = onReadPrivacyPolicy,
                        onAccept = { onFinishOnboarding() }
                    )
            }
        }
    }
}

@Preview2
@Composable
private fun OnboardingScreenPreview() {
    PreviewWrapper {
        OnboardingScreen(
            state = OnboardingViewModel.State(),
            onUpdateCheckChange = {},
            onMotdCheckChange = {},
            onReadPrivacyPolicy = {},
            onFinishOnboarding = {},
        )
    }
}