package testhelpers

import eu.darken.butler.common.JUnitLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import io.mockk.unmockkAll
import org.junit.AfterClass
import org.junit.jupiter.api.AfterAll


open class BaseTest {
    init {
        Logging.clearAll()
        Logging.install(JUnitLogger())
        testClassName = this.javaClass.simpleName
    }

    companion object {
        @Volatile
        private var testClassName: String? = null

        private fun tearDownTestClass() {
            unmockkAll()
            log(testClassName ?: "BaseTest", VERBOSE) { "onTestClassFinished()" }
            Logging.clearAll()
        }

        // JUnit 5 (jupiter engine)
        @JvmStatic
        @AfterAll
        fun onTestClassFinished() = tearDownTestClass()

        // JUnit 4 (vintage engine) — Robolectric test classes are claimed by vintage, which
        // ignores @AfterAll, so without this the cleanup never runs for them.
        @JvmStatic
        @AfterClass
        fun onTestClassFinishedJUnit4() = tearDownTestClass()
    }
}
