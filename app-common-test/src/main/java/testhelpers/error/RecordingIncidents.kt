package testhelpers.error

import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.error.ErrorIncidentStore
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Freezes real [ErrorIncident]s (a relaxed mock hands back a mocked throwable, which the states
 * under test read through), with a fresh id per call so a test can tell a re-freeze from a
 * carried-over incident. With a [spoolDir] it also writes one file per freeze, which is what a test
 * counting log trails goes by.
 */
fun recordingIncidentFactory(spoolDir: File? = null): ErrorIncidentFactory = mockk {
    var counter = 0
    coEvery { freeze(any(), any(), any()) } answers {
        val incidentId = "incident-${counter++}"
        ErrorIncident(
            incidentId = incidentId,
            occurredAt = thirdArg<Instant?>() ?: Clock.System.now(),
            occurredAtIsApproximate = thirdArg<Instant?>() == null,
            error = firstArg(),
            context = secondArg<Map<String, String?>>().filterValues { it != null }.mapValues { it.value!! },
            logFile = spoolDir?.let { dir ->
                dir.mkdirs()
                File(dir, "$incidentId.log").apply { writeText("log trail") }
            },
        )
    }
}

/** The real store, so identity keying and mint-once behave as they do in production. */
fun recordingIncidentStore(spoolDir: File? = null): ErrorIncidentStore =
    ErrorIncidentStore(recordingIncidentFactory(spoolDir))
