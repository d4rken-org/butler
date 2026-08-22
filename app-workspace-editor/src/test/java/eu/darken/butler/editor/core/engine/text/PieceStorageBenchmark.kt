package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.SearchOptions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.random.Random
import kotlin.time.measureTime

/**
 * NOT a test - a decision-gate benchmark for the flat-list piece storage (ArrayList + prefix
 * sums rebuilt O(pieces) per edit) vs a balanced piece tree.
 *
 * The `manual-benchmark` tag is excluded from the module's normal `Test` tasks, so this class is
 * absent from the test plan (not reported as skipped). Run it manually with:
 *
 * `./gradlew :app-workspace-editor:testFossDebugUnitTest -PrunEditorBenchmarks --tests "*PieceStorageBenchmark*"`
 *
 * Decision rule: consider a piece tree only if per-edit latency at >= 10k pieces exceeds ~1ms
 * or UI jank is otherwise plausible. Storage is internal to PieceTable, so a later swap needs
 * no API change.
 */
@Tag("manual-benchmark")
class PieceStorageBenchmark : BaseTest() {

    @Test
    fun `scattered edits with line lookups and reads at increasing piece counts`() = runTest {
        // ~5 MiB document, 80-char lines
        val content = buildString {
            val line = "a".repeat(79)
            repeat(65_536) {
                append(line)
                append('\n')
            }
        }
        val table = PieceTable.create(StringOriginalDocument(content))
        val random = Random(42)

        // Mid-piece inserts split one piece into three: pieces ~ 2x edits
        val checkpoints = listOf(500, 5_000, 25_000, 50_000)
        var edits = 0
        println("doc=${table.totalCharLength} chars, ${table.totalLineBreaks} breaks")
        for (target in checkpoints) {
            val segment = target - edits
            val editTime = measureTime {
                while (edits < target) {
                    val offset = random.nextLong(table.totalCharLength)
                    table.insert(offset, "x")
                    edits++
                }
            }
            val pieces = table.pieceSnapshot().size
            val lineLookups = 200
            val lineTime = measureTime {
                repeat(lineLookups) {
                    table.lineStartOffset(random.nextLong(table.totalLineBreaks) + 1)
                }
            }
            val reads = 200
            val readTime = measureTime {
                repeat(reads) {
                    val start = random.nextLong(table.totalCharLength - 1_000)
                    table.read(start, start + 1_000)
                }
            }
            val searchTime = measureTime {
                WindowedSearch { start, end -> table.read(start, end) }
                    .search(table.totalCharLength, "needle-not-present", SearchOptions(caseSensitive = true))
            }
            println(
                "edits=$edits pieces=$pieces " +
                    "perEdit=${editTime / segment} " +
                    "lineLookup=${lineTime / lineLookups} " +
                    "read1k=${readTime / reads} " +
                    "fullSearch=$searchTime",
            )
        }
    }
}
