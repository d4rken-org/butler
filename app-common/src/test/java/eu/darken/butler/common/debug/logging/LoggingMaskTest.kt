package eu.darken.butler.common.debug.logging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoggingMaskTest {

    private class ThresholdLogger(var floor: Logging.Priority) : Logging.Logger {
        override fun isLoggable(priority: Logging.Priority): Boolean = priority.intValue >= floor.intValue
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {}
    }

    @BeforeEach
    fun setup() = Logging.clearAll()

    @AfterEach
    fun tearDown() = Logging.clearAll()

    @Test
    fun `no loggers means nothing is loggable`() {
        Logging.isLoggable(Logging.Priority.ERROR) shouldBe false
        Logging.isLoggable(Logging.Priority.VERBOSE) shouldBe false
    }

    @Test
    fun `mask reflects an installed logger's threshold`() {
        Logging.install(ThresholdLogger(Logging.Priority.WARN))
        Logging.isLoggable(Logging.Priority.DEBUG) shouldBe false
        Logging.isLoggable(Logging.Priority.INFO) shouldBe false
        Logging.isLoggable(Logging.Priority.WARN) shouldBe true
        Logging.isLoggable(Logging.Priority.ERROR) shouldBe true
    }

    @Test
    fun `mask is the union across loggers`() {
        Logging.install(ThresholdLogger(Logging.Priority.ERROR))
        Logging.install(ThresholdLogger(Logging.Priority.DEBUG))
        Logging.isLoggable(Logging.Priority.DEBUG) shouldBe true
        Logging.isLoggable(Logging.Priority.VERBOSE) shouldBe false
    }

    @Test
    fun `removing a logger recomputes the mask`() {
        val logger = ThresholdLogger(Logging.Priority.VERBOSE)
        Logging.install(logger)
        Logging.isLoggable(Logging.Priority.VERBOSE) shouldBe true
        Logging.remove(logger)
        Logging.isLoggable(Logging.Priority.VERBOSE) shouldBe false
    }

    @Test
    fun `refreshLoggable picks up a mutated threshold`() {
        val logger = ThresholdLogger(Logging.Priority.ERROR)
        Logging.install(logger)
        Logging.isLoggable(Logging.Priority.DEBUG) shouldBe false

        logger.floor = Logging.Priority.DEBUG
        Logging.refreshLoggable()
        Logging.isLoggable(Logging.Priority.DEBUG) shouldBe true
    }
}
