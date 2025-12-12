package eu.darken.butler.debug.core

import eu.darken.butler.common.debug.logging.Logging.Priority
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class BufferedLoggerTest : BaseTest() {

    @Test
    fun `logs are buffered in StateFlow`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "TestTag", "Test message", null)

        logger.logLines.value.size shouldBe 1
        logger.logLines.value.first().let { line ->
            line.priority shouldBe Priority.INFO
            line.tag shouldBe "TestTag"
            line.message shouldBe "Test message"
        }
    }

    @Test
    fun `multiple logs are buffered in order`() {
        val logger = BufferedLogger()

        logger.log(Priority.DEBUG, "Tag1", "Message 1", null)
        logger.log(Priority.INFO, "Tag2", "Message 2", null)
        logger.log(Priority.WARN, "Tag3", "Message 3", null)

        logger.logLines.value.size shouldBe 3
        logger.logLines.value[0].message shouldBe "Message 1"
        logger.logLines.value[1].message shouldBe "Message 2"
        logger.logLines.value[2].message shouldBe "Message 3"
    }

    @Test
    fun `excluded tag prefix Debug Workspace is filtered`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "BTLR:Debug:Workspace:abc:Page", "Should be filtered", null)
        logger.log(Priority.INFO, "BTLR:Other:Tag", "Should pass", null)

        logger.logLines.value.size shouldBe 1
        logger.logLines.value.first().message shouldBe "Should pass"
    }

    @Test
    fun `excluded tag prefix Debug LogRepo is filtered`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "BTLR:Debug:LogRepo", "Should be filtered", null)
        logger.log(Priority.INFO, "BTLR:Other:Tag", "Should pass", null)

        logger.logLines.value.size shouldBe 1
        logger.logLines.value.first().message shouldBe "Should pass"
    }

    @Test
    fun `excluded tag prefix Debug TestDataGenerator is filtered`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "BTLR:Debug:TestDataGenerator", "Should be filtered", null)
        logger.log(Priority.INFO, "BTLR:Other:Tag", "Should pass", null)

        logger.logLines.value.size shouldBe 1
        logger.logLines.value.first().message shouldBe "Should pass"
    }

    @Test
    fun `non-excluded tags are captured`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "BTLR:Explorer:Workspace:abc", "Explorer log", null)
        logger.log(Priority.INFO, "BTLR:Searcher:Engine", "Searcher log", null)
        logger.log(Priority.INFO, "BTLR:Editor:Buffer", "Editor log", null)

        logger.logLines.value.size shouldBe 3
    }

    @Test
    fun `buffer respects max lines limit`() {
        val logger = BufferedLogger(maxLines = 5)

        repeat(10) { i ->
            logger.log(Priority.DEBUG, "Tag", "Message $i", null)
        }

        logger.logLines.value.size shouldBe 5
        // Should keep the last 5 messages (5-9)
        logger.logLines.value[0].message shouldBe "Message 5"
        logger.logLines.value[4].message shouldBe "Message 9"
    }

    @Test
    fun `clear removes all buffered logs`() {
        val logger = BufferedLogger()

        logger.log(Priority.INFO, "Tag", "Message 1", null)
        logger.log(Priority.INFO, "Tag", "Message 2", null)
        logger.log(Priority.INFO, "Tag", "Message 3", null)

        logger.logLines.value.size shouldBe 3

        logger.clear()

        logger.logLines.value.size shouldBe 0
    }

    @Test
    fun `LogLine format produces correct output`() {
        val logger = BufferedLogger()

        logger.log(Priority.WARN, "MyTag", "My message", null)

        val formatted = logger.logLines.value.first().format()
        formatted shouldBe "W/MyTag: My message"
    }

    @Test
    fun `LogLine format works for all priority levels`() {
        val logger = BufferedLogger()

        logger.log(Priority.VERBOSE, "Tag", "verbose", null)
        logger.log(Priority.DEBUG, "Tag", "debug", null)
        logger.log(Priority.INFO, "Tag", "info", null)
        logger.log(Priority.WARN, "Tag", "warn", null)
        logger.log(Priority.ERROR, "Tag", "error", null)
        logger.log(Priority.ASSERT, "Tag", "assert", null)

        val lines = logger.logLines.value
        lines[0].format() shouldBe "V/Tag: verbose"
        lines[1].format() shouldBe "D/Tag: debug"
        lines[2].format() shouldBe "I/Tag: info"
        lines[3].format() shouldBe "W/Tag: warn"
        lines[4].format() shouldBe "E/Tag: error"
        lines[5].format() shouldBe "WTF/Tag: assert"
    }

    @Test
    fun `default max lines is 500`() {
        BufferedLogger.DEFAULT_MAX_LINES shouldBe 500
    }
}
