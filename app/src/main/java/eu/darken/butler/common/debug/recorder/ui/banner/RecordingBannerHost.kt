package eu.darken.butler.common.debug.recorder.ui.banner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.debug.recorder.ui.ShortRecordingDialog
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun RecordingBannerHost(
    modifier: Modifier = Modifier,
    vm: RecordingBannerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState(initial = null)
    val currentState = state ?: return

    var elapsedTime by remember { mutableStateOf(Duration.ZERO) }
    var showShortRecordingWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                RecordingBannerViewModel.Event.ShowShortRecordingWarning -> {
                    showShortRecordingWarning = true
                }
            }
        }
    }

    // Update elapsed time every second while recording
    LaunchedEffect(currentState.isRecording, currentState.recordingStartedAt) {
        if (currentState.isRecording && currentState.recordingStartedAt > 0L) {
            val startTime = Instant.fromEpochMilliseconds(currentState.recordingStartedAt)
            while (true) {
                elapsedTime = Clock.System.now() - startTime
                delay(1.seconds)
            }
        } else {
            elapsedTime = Duration.ZERO
        }
    }

    if (showShortRecordingWarning) {
        ShortRecordingDialog(
            onKeepRecording = { showShortRecordingWarning = false },
            onStopAnyway = {
                showShortRecordingWarning = false
                vm.forceStopRecording()
            },
        )
    }

    RecordingBanner(
        modifier = modifier,
        visible = currentState.isRecording,
        elapsedTime = elapsedTime,
        logSize = currentState.currentLogSize,
        onStop = vm::stopRecording,
    )
}
