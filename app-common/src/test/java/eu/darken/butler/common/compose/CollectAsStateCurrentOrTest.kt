package eu.darken.butler.common.compose

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import testhelpers.ComposeTest

class CollectAsStateCurrentOrTest : ComposeTest() {

    @Test
    fun `StateFlow starts at its current value and follows updates`() {
        val source = MutableStateFlow("current")
        val recorded = mutableListOf<String>()

        composeTestRule.setContent {
            val state = source.collectAsStateCurrentOr("fallback")
            recorded += state.value
        }
        composeTestRule.waitForIdle()

        recorded.first() shouldBe "current"

        source.value = "next"
        composeTestRule.waitForIdle()

        recorded.last() shouldBe "next"
    }

    @Test
    fun `other flows start at the fallback and follow emissions`() {
        val source = MutableSharedFlow<String>()
        val recorded = mutableListOf<String>()

        composeTestRule.setContent {
            val state = source.collectAsStateCurrentOr("fallback")
            recorded += state.value
        }
        composeTestRule.waitForIdle()

        recorded.first() shouldBe "fallback"

        runBlocking { source.emit("emitted") }
        composeTestRule.waitForIdle()

        recorded.last() shouldBe "emitted"
    }
}
