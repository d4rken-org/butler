package eu.darken.butler.common.debug.logviewer.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import eu.darken.butler.R
import eu.darken.butler.common.error.ErrorEventHandler

/**
 * Activity-scoped host for the floating log panel.
 *
 * Placed as a sibling of `NavDisplay` in `MainActivity` (not a nav destination), so `hiltViewModel()`
 * resolves to the Activity's `ViewModelStoreOwner` and the panel survives screen navigation. The
 * lifecycle effect always runs (even while not rendered) so capture can be gated on foreground
 * state; [LifecycleStartEffect] catches up immediately when this composable first enters the
 * composition with the lifecycle already STARTED.
 */
@Composable
fun FloatingLogPanelHost(
    modifier: Modifier = Modifier,
    vm: FloatingLogPanelViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    val context = LocalContext.current

    LifecycleStartEffect(vm) {
        vm.setLifecycleStarted(true)
        onStopOrDispose { vm.setLifecycleStarted(false) }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is FloatingLogPanelViewModel.Event.LaunchShare -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    vm.onShareLaunchFailed(e)
                }

                is FloatingLogPanelViewModel.Event.Copied -> {
                    val message = if (event.truncatedBy > 0) {
                        context.getString(R.string.debug_logview_copied_truncated_msg, FloatingLogPanelViewModel.COPY_LINE_CAP)
                    } else {
                        context.getString(R.string.debug_logview_copied_msg)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val rendered by vm.isRendered.collectAsState()
    if (!rendered) return

    FloatingLogPanel(
        modifier = modifier,
        stateSource = vm.state,
        onSetQuery = vm::setQuery,
        onNextMatch = vm::nextMatch,
        onPrevMatch = vm::prevMatch,
        onTogglePause = vm::togglePause,
        onSetLevel = vm::setDisplayPriority,
        onClear = vm::clearBuffer,
        onCopy = vm::copyAll,
        onShare = vm::shareAll,
        onClose = vm::close,
    )
}
