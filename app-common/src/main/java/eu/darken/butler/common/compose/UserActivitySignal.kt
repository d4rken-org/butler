package eu.darken.butler.common.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Whether the user is currently working with the app, so decorative animations can stop while
 * nobody is watching. A single animated pixel invalidates the whole Compose view, which keeps the
 * window redrawing at panel rate for as long as anything animates.
 */
interface UserActivitySignal {

    /** Emits `true` while the last interaction is younger than [idleAfter], `false` afterwards. */
    fun isActive(idleAfter: Duration): Flow<Boolean>

    /** For previews and tests, where there is no activity to receive input events. */
    object AlwaysActive : UserActivitySignal {
        override fun isActive(idleAfter: Duration): Flow<Boolean> = flowOf(true)
    }
}

@Singleton
@Stable
class UserActivityTracker internal constructor(
    private val timeSource: TimeSource,
) : UserActivitySignal {

    @Inject constructor() : this(TimeSource.Monotonic)

    // Process start counts as an interaction, the user just opened the app.
    private val lastInteraction = MutableStateFlow(timeSource.markNow())

    fun onUserInteraction() {
        lastInteraction.value = timeSource.markNow()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isActive(idleAfter: Duration): Flow<Boolean> = lastInteraction
        .flatMapLatest { mark ->
            flow {
                // Collectors start at arbitrary times, so the window is what's left of it, not all of it
                val remaining = idleAfter - mark.elapsedNow()
                if (remaining > Duration.ZERO) {
                    emit(true)
                    delay(remaining)
                }
                emit(false)
            }
        }
        .distinctUntilChanged()
}

val LocalUserActivity: ProvidableCompositionLocal<UserActivitySignal> =
    staticCompositionLocalOf { UserActivitySignal.AlwaysActive }
