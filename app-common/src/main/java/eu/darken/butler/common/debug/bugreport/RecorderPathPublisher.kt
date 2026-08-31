package eu.darken.butler.common.debug.bugreport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The report directory of the recording that is running right now, handed to the root and ADB
 * service clients so they can forward it to their host processes, which append their own log next to
 * `report.log`.
 *
 * An output channel, never a resume marker: [BugReportRecorder] alone decides whether a recording is
 * live, via the `.recording` sentinel, and republishes the path here once it has claimed a session
 * (fresh start or startup resume). Deliberately process-local and never persisted, so a fresh
 * process starts at `null` until the recorder has claimed one, and so publishing can neither suspend
 * nor fail (a write that throws would abort an otherwise working recording).
 */
@Singleton
class RecorderPathPublisher @Inject constructor() {

    private val internalPath = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = internalPath.asStateFlow()

    fun publish(path: String?) {
        internalPath.value = path
    }
}
