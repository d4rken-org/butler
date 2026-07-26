package eu.darken.flowshell.core

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A gated subprocess: the shell instruction announces that it started and then blocks until the
 * test releases it.
 *
 * Lets a test prove "the command is still running" / "the command was aborted" structurally,
 * instead of asserting elapsed wall-clock time.
 */
class ShellGate(dir: File, name: String = "gate") {

    private val startedMarker = File(dir, "$name-started")
    private val releaseMarker = File(dir, "$name-release")

    /** Announces the start, then blocks until [release] is called. */
    val instruction = "touch '${startedMarker.path}'; " +
        "while [ ! -f '${releaseMarker.path}' ]; do sleep 0.05; done"

    val wasReleased: Boolean
        get() = releaseMarker.exists()

    val hasStarted: Boolean
        get() = startedMarker.exists()

    /** Blocks until the gated command signalled that it is running. The timeout is a watchdog. */
    fun awaitStarted(timeout: Duration = WATCHDOG) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!startedMarker.exists()) {
            check(System.nanoTime() < deadline) { "Gated command never started: $startedMarker" }
            Thread.sleep(10)
        }
    }

    fun release() {
        releaseMarker.createNewFile()
    }

    companion object {
        val WATCHDOG = 30.seconds
    }
}
