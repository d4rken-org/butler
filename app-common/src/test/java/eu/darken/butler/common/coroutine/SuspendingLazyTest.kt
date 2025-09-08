package eu.darken.butler.common.coroutine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class SuspendingLazyTest : BaseTest() {

    @Test
    fun `value is cached`() = runTest {
        val suspendingLazy = SuspendingLazy { Uuid.random().toString() }
        suspendingLazy.value() shouldBe suspendingLazy.value()
    }
}
