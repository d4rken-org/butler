package eu.darken.butler.history.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The shared markdown is the record a user pastes elsewhere, arbitrary path text included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryShareTextTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val completedAt = Instant.parse("2026-09-02T14:31:05Z")

    private fun entry(
        id: String = "entry",
        title: String = "Copy 5 items",
        description: String = "5 items to /backup",
        summary: String? = null,
        outcome: HistoryOutcome = HistoryOutcome.COMPLETED,
        errorMessage: String? = null,
        partialErrorCount: Int = 0,
        affectedPathsCount: Int = 1,
        pathsTruncated: Boolean = false,
        paths: List<HistoryEntry.PathChange> = listOf(
            HistoryEntry.PathChange(
                path = "/storage/emulated/0/DCIM/photo.jpg",
                previousPath = null,
                change = Operation.Report.PathChange.Change.ADDED,
            ),
        ),
    ) = HistoryEntry(
        id = id,
        kind = Operation.Metadata.Kind.COPY,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = title,
        description = description,
        summary = summary,
        startedAt = completedAt - 1200.milliseconds,
        completedAt = completedAt,
        duration = 1200.milliseconds,
        outcome = outcome,
        errorMessage = errorMessage,
        errorClass = null,
        affectedPathsCount = affectedPathsCount,
        partialErrorCount = partialErrorCount,
        pathsTruncated = pathsTruncated,
        paths = paths,
    )

    private fun share(
        entries: List<HistoryEntry>,
        attemptedPaths: OperationHistoryRepo.AttemptedPaths? = null,
    ) = buildHistoryShareText(
        context = context,
        entries = entries,
        attemptedPaths = attemptedPaths,
        zone = ZoneOffset.UTC,
    )

    @Test
    fun `a completed entry renders its heading, meta lines and paths`() {
        val text = share(listOf(entry(summary = "5 files copied")))

        text shouldContain "## Copy 5 items\n5 items to /backup\n"
        text shouldContain "- **Outcome:** Completed\n"
        text shouldContain "- **Kind:** Copy\n"
        text shouldContain "- **Origin:** Explorer\n"
        text shouldContain "- **Summary:** 5 files copied\n"
        text shouldContain "- **Completed:** 2026-09-02 14:31:05\n"
        text shouldContain "- **Duration:** 1.2 s\n"
        text shouldContain "**Affected paths (1)**\n"
        text shouldContain "- added: `/storage/emulated/0/DCIM/photo.jpg`"
    }

    @Test
    fun `a renamed path renders both sides`() {
        val text = share(
            listOf(
                entry(
                    paths = listOf(
                        HistoryEntry.PathChange(
                            path = "/sdcard/new.txt",
                            previousPath = "/sdcard/old.txt",
                            change = Operation.Report.PathChange.Change.MOVED,
                        ),
                    ),
                )
            )
        )

        text shouldContain "- `/sdcard/old.txt` → `/sdcard/new.txt`"
    }

    @Test
    fun `a truncated path list says how many are missing`() {
        val text = share(
            listOf(
                entry(
                    affectedPathsCount = 200,
                    pathsTruncated = true,
                    paths = (1..5).map {
                        HistoryEntry.PathChange(
                            path = "/sdcard/file_$it.bin",
                            previousPath = null,
                            change = Operation.Report.PathChange.Change.REMOVED,
                        )
                    },
                )
            )
        )

        text shouldContain "… and 195 more"
    }

    @Test
    fun `only a failed entry carries an error line`() {
        val failed = share(
            listOf(
                entry(
                    outcome = HistoryOutcome.FAILED,
                    errorMessage = "Permission denied",
                    partialErrorCount = 3,
                )
            )
        )
        val completed = share(listOf(entry()))

        failed shouldContain "- **Error:** Permission denied\n"
        failed shouldContain "- **Failed items:** 3\n"
        completed shouldNotContain "**Error:**"
        completed shouldNotContain "**Failed items:**"
    }

    @Test
    fun `two entries become two blocks separated by a blank line`() {
        val text = share(listOf(entry(id = "a", title = "First"), entry(id = "b", title = "Second")))

        text shouldContain "\n\n## Second"
        text.split("## ").size shouldBe 3
    }

    @Test
    fun `an entry without reported paths falls back to the attempted ones`() {
        val text = share(
            entries = listOf(entry(affectedPathsCount = 0, paths = emptyList())),
            attemptedPaths = OperationHistoryRepo.AttemptedPaths(
                paths = listOf("/sdcard/ButlerQA", "/sdcard/ButlerQA/notes.txt"),
                totalCount = 12,
            ),
        )

        text shouldContain "No affected paths recorded."
        text shouldContain "**Attempted paths**"
        text shouldContain "- `/sdcard/ButlerQA/notes.txt`"
        text shouldContain "Showing first 2 of 12 attempted paths."
    }

    @Test
    fun `an entry without any paths says so`() {
        val text = share(listOf(entry(affectedPathsCount = 0, paths = emptyList())))

        text shouldContain "No affected paths recorded."
        text shouldNotContain "Attempted paths"
    }

    @Test
    fun `a backtick in a path and a newline in an error do not break the document`() {
        val text = share(
            listOf(
                entry(
                    outcome = HistoryOutcome.FAILED,
                    errorMessage = "Permission denied\n# not a heading\n- not a list item",
                    paths = listOf(
                        HistoryEntry.PathChange(
                            path = "/sdcard/we`ird`.txt",
                            previousPath = null,
                            change = Operation.Report.PathChange.Change.ADDED,
                        ),
                    ),
                )
            )
        )

        text shouldContain "- **Error:** Permission denied # not a heading - not a list item\n"
        text shouldContain "- added: ``/sdcard/we`ird`.txt``"
        text.lines().none { it.startsWith("# ") } shouldBe true
    }

    @Test
    fun `a select-all share stays within the size budget`() {
        val entries = (1..2000).map { index ->
            entry(
                id = "entry-$index",
                affectedPathsCount = 200,
                paths = (1..200).map {
                    HistoryEntry.PathChange(
                        path = "/storage/emulated/0/DCIM/folder_$index/photo_$it.jpg",
                        previousPath = null,
                        change = Operation.Report.PathChange.Change.ADDED,
                    )
                },
            )
        }

        val text = share(entries)

        (text.length < SHARE_TEXT_MAX_CHARS) shouldBe true
        text shouldEndWith "more entries not included"
    }

    @Test
    fun `an entry sharing the same zone renders the completion time in it`() {
        val text = buildHistoryShareText(
            context = context,
            entries = listOf(entry()),
            zone = ZoneOffset.ofHours(2),
        )

        text shouldContain "- **Completed:** 2026-09-02 16:31:05\n"
    }

    @Test
    fun `a duration below a second is rendered in milliseconds`() {
        val entry = entry().copy(duration = 250.milliseconds)

        share(listOf(entry)) shouldContain "- **Duration:** 250 ms\n"
    }

    @Test
    fun `a long duration keeps one decimal`() {
        val entry = entry().copy(duration = 25.seconds)

        share(listOf(entry)) shouldContain "- **Duration:** 25.0 s\n"
    }

    private fun added(path: String) = HistoryEntry.PathChange(
        path = path,
        previousPath = null,
        change = Operation.Report.PathChange.Change.ADDED,
    )

    /**
     * The loop stops at the first block that does not fit, so the truncation notice is appended to
     * whatever headroom is left. Sized so the notice needs more room than remains: 101 identical
     * blocks of [TARGET_BLOCK_CHARS], of which 100 fit exactly.
     */
    @Test
    fun `the truncation notice stays inside the size budget`() {
        val probePath = "/sdcard/pad/"
        val padding = TARGET_BLOCK_CHARS - share(listOf(entry(paths = listOf(added(probePath))))).length
        val path = probePath + "p".repeat(padding)
        val entries = (1..101).map { entry(id = "entry-$it", paths = listOf(added(path))) }

        val text = share(entries)

        text shouldEndWith "more entries not included"
        text.length shouldBeLessThanOrEqual SHARE_TEXT_MAX_CHARS
    }

    @Test
    fun `a newline in a path does not break out of its code span`() {
        val text = share(listOf(entry(paths = listOf(added("/sdcard/we\nird.txt")))))

        val pathLine = text.lines().single { it.startsWith("- added: ") }
        pathLine shouldEndWith "`"
        pathLine shouldContain "we\\nird.txt"
    }

    @Test
    fun `a carriage return in a path does not break out of its code span`() {
        val text = share(listOf(entry(paths = listOf(added("/sdcard/we\rird.txt")))))

        val pathLine = text.lines().single { it.startsWith("- added: ") }
        pathLine shouldEndWith "`"
        pathLine shouldContain "we\\rird.txt"
        text shouldNotContain "\r"
    }

    @Test
    fun `a literal backslash-n in a path stays distinct from a real newline`() {
        val literal = share(listOf(entry(paths = listOf(added("/sdcard/we\\nird.txt")))))
        val real = share(listOf(entry(paths = listOf(added("/sdcard/we\nird.txt")))))

        val literalLine = literal.lines().single { it.startsWith("- added: ") }
        literalLine shouldBe "- added: `/sdcard/we\\\\nird.txt`"
        (literalLine == real.lines().single { it.startsWith("- added: ") }) shouldBe false
    }
}

/** 101 of these leaves 2 chars of headroom, less than the truncation notice needs. */
private const val TARGET_BLOCK_CHARS = 998
