package testhelpers

import eu.darken.butler.common.BuildConfig
import eu.darken.butler.common.JUnitLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterAll
import java.io.File
import kotlin.uuid.Uuid


open class BaseTest {
    init {
        Logging.clearAll()
        Logging.install(JUnitLogger())
        testClassName = this.javaClass.simpleName
    }

    companion object {
        private var testClassName: String? = null
        val IO_TEST_BASEDIR: File = File(
            "build/tmp/unit_tests_${BuildConfig.BUILD_TYPE.uppercase()}_${Uuid.random().toString().take(4)}"
        ).absoluteFile

        @JvmStatic
        @AfterAll
        fun onTestClassFinished() {
            unmockkAll()
            log(testClassName!!, VERBOSE) { "onTestClassFinished()" }
            Logging.clearAll()
        }
    }
}
