package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.operations.ExtractOperationReport
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class ExtractRevealTest : BaseTest() {

    private val destinationDir = LocalPath.build("/storage/emulated/0/Download")
    private val archive = destinationDir.child("photos.zip")
    private val outputDir = destinationDir.child("photos (1)")

    private fun completed(
        report: Operation.Report?,
        error: Throwable? = null,
    ): Operation.State.Completed = object : Operation.State.Completed {
        override val startedAt: Instant = Instant.fromEpochMilliseconds(0)
        override val completedAt: Instant = Instant.fromEpochMilliseconds(1)
        override val summary: CaString = "".toCaString()
        override val report: Operation.Report? = report
        override val error: Throwable? = error
    }

    private fun extractReport(outputDir: LocalPath?) = ExtractOperationReport.Builder(archive)
        .apply { outputDir?.let { setOutputDir(it) } }
        .build()

    @Test
    fun `a failed extraction reveals nothing`() {
        extractReveal(
            completed(extractReport(outputDir), error = IllegalStateException("boom")),
            destinationDir,
            destinationArrived = true,
        ) shouldBe null
    }

    @Test
    fun `an extraction that created no folder reveals nothing`() {
        extractReveal(completed(extractReport(null)), destinationDir, destinationArrived = true) shouldBe null
    }

    @Test
    fun `a report that is not an extraction report reveals nothing`() {
        val packages = Operation.Report.Packages(summary = "".toCaString(), outcomes = emptyList())

        extractReveal(completed(packages), destinationDir, destinationArrived = true) shouldBe null
    }

    @Test
    fun `an arrived destination makes the reveal wait for that folder`() {
        extractReveal(completed(extractReport(outputDir)), destinationDir, destinationArrived = true) shouldBe
            ExtractReveal(
                path = outputDir,
                destination = ExplorerLocation.Directory.locationIdOf(destinationDir),
                awaitDestinationLoad = true,
            )
    }

    @Test
    fun `a destination that never arrived reveals whatever is on screen`() {
        extractReveal(completed(extractReport(outputDir)), destinationDir, destinationArrived = false) shouldBe
            ExtractReveal(
                path = outputDir,
                destination = null,
                awaitDestinationLoad = false,
            )
    }
}
