package eu.darken.butler.main.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.main.ui.onboarding.OnboardingViewModel.State.*
import eu.darken.butler.main.ui.onboarding.components.OnboardingProgressBar
import eu.darken.butler.main.ui.onboarding.pages.BetaPage
import eu.darken.butler.main.ui.onboarding.pages.PrivacyPage
import eu.darken.butler.main.ui.onboarding.pages.WelcomePage
import eu.darken.butler.main.ui.onboarding.pages.WorkspacesPage
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreenHost(vm: OnboardingViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

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

    val lastPage = Page.entries.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        OnboardingProgressBar(
            currentPage = pagerState.currentPage,
            pageCount = Page.entries.size,
            onSkip = if (pagerState.currentPage < lastPage) {
                { scope.launch { pagerState.animateScrollToPage(lastPage) } }
            } else {
                null
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OnboardingScreenPreview() {
    OnboardingScreen(
        state = OnboardingViewModel.State(),
        onUpdateCheckChange = {},
        onMotdCheckChange = {},
        onReadPrivacyPolicy = {},
        onFinishOnboarding = {},
    )
}