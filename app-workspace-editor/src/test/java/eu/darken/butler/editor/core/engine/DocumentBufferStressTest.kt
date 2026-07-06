package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.measureTime

/**
 * Stress sanity: thousands of small edits against a reference model, with line-lookup and
 * search timings logged. Timings are informational, not a gate.
 */
class DocumentBufferStressTest : DocumentBufferTestBase() {

    @Test
    fun `thousands of small edits stay correct`() = runTest {
        val initial = (1..200).joinToString("\n") { "line $it content" }
        val buffer = createBuffer(initial, blockSize = 256)
        val reference = StringBuilder(initial)
        val random = Random(1337)

        val editTime = measureTime {
            repeat(2000) {
                if (random.nextInt(4) == 0 && reference.isNotEmpty()) {
                    val start = random.nextInt(reference.length)
                    val end = minOf(reference.length, start + 1 + random.nextInt(4))
                    buffer.deleteText(
                        TextPosition(start.toLong(), 0, 0),
                        TextPosition(end.toLong(), 0, 0),
                    ).getOrThrow()
                    reference.delete(start, end)
                } else {
                    val offset = random.nextInt(reference.length + 1)
                    buffer.insertText(TextPosition(offset.toLong(), 0, 0), "ab").getOrThrow()
                    reference.insert(offset, "ab")
                }
            }
        }

        buffer.getFullText().getOrThrow() shouldBe reference.toString()

        val lineTime = measureTime {
            repeat(500) {
                buffer.getTextForLine(random.nextLong(buffer.totalLines.value)).getOrThrow()
            }
        }
        val searchTime = measureTime {
            buffer.search("content", options = SearchOptions(caseSensitive = true))
        }
        log("DocumentBufferStressTest", INFO) {
            "2000 edits=$editTime, 500 line lookups=$lineTime, one search=$searchTime"
        }
    }
}
