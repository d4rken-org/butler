package eu.darken.flowshell.core

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A gated subprocess: the shell instruction records its own pid, announces that it started and then
 * blocks until the test releases it.
 *
 * Lets a test prove "the command is still running" / "the command was aborted" / "the process is
 * actually dead" structurally, instead of asserting elapsed wall-clock time.
 *
 * Every test using a gate must [release] it in a `finally`, otherwise a failed kill would strand a
 * subprocess spinning forever on a release marker that is deleted with the temp dir.
 */
class ShellGate(dir: File, name: String = "gate") {

    private val pidMarker = File(dir, "$name-pid")
    private val startedMarker = File(dir, "$name-started")
    private val releaseMarker = File(dir, "$name-release")

    /** Records the pid, announces the start, then blocks until [release] is called. */
    val instruction = "echo ${'$'}${'$'} > '${pidMarker.path}'; " +
        "touch '${startedMarker.path}'; " +
        "while [ ! -f '${releaseMarker.path}' ]; do sleep 0.05; done"

    /** The pid of the shell running the gated instruction, `null` until it started. */
    val pid: Int?
        get() = pidMarker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    /** Checked from the outside, so it stays true even if the production code hides the process. */
    fun isAlive(): Boolean {
        val pid = pid ?: return false
        return ProcessBuilder("sh", "-c", "kill -0 $pid 2>/dev/null").start().waitFor() == 0
    }

    /** Blocks until the gated command signalled that it is running. The timeout is a watchdog. */
    fun awaitStarted(timeout: Duration = WATCHDOG) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!startedMarker.exists()) {
            check(System.nanoTime() < deadline) { "Gated command never started: $startedMarker" }
            Thread.sleep(10)
        }
    }

    /** Blocks until the gated process is gone. Killing is asynchronous, the timeout is a watchdog. */
    fun awaitDeath(timeout: Duration = WATCHDOG) {
        val pid = checkNotNull(pid) { "Gated command never recorded a pid: $pidMarker" }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (isAlive()) {
            check(System.nanoTime() < deadline) { "Gated process $pid is still alive" }
            Thread.sleep(10)
        }
    }

    fun release() {
        releaseMarker.createNewFile()
    }

    /**
     * Releases the gate and makes sure nothing is left spinning. Releasing alone is not enough:
     * the marker lives in a temp dir that is deleted right after the test, so a shell that polls
     * after the deletion would never see it and spin forever. The recorded pid is the backstop.
     */
    fun shutdown() {
        release()
        val pid = pid ?: return
        val deadline = System.nanoTime() + GRACE.inWholeNanoseconds
        while (isAlive() && System.nanoTime() < deadline) Thread.sleep(10)
        if (isAlive()) ProcessBuilder("sh", "-c", "kill -9 $pid").start().waitFor()
    }

    companion object {
        val WATCHDOG = 30.seconds
        private val GRACE = 2.seconds
    }
}
