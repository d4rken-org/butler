package eu.darken.butler.common.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class NavigationControllerTest : BaseTest() {

    private data class Screen(val name: String) : NavigationDestination

    private val home = Screen("home")
    private val settings = Screen("settings")
    private val picker = Screen("picker")
    private val other = Screen("other")

    private fun controller(vararg entries: NavKey): Pair<NavigationController, NavBackStack<NavKey>> {
        val backStack = NavBackStack(*entries)
        return NavigationController().apply { setup(backStack) } to backStack
    }

    @Test
    fun `dismiss removes the entry on top`() {
        val (controller, backStack) = controller(home, settings, picker)

        controller.dismiss(picker) shouldBe true

        backStack.toList() shouldContainExactly listOf(home, settings)
    }

    @Test
    fun `dismiss removes a covered entry and leaves what is on top of it`() {
        val (controller, backStack) = controller(home, settings, picker, other)

        controller.dismiss(picker) shouldBe true

        backStack.toList() shouldContainExactly listOf(home, settings, other)
    }

    @Test
    fun `dismissing an absent entry changes nothing`() {
        val (controller, backStack) = controller(home, settings)

        controller.dismiss(picker) shouldBe false

        backStack.toList() shouldContainExactly listOf(home, settings)
    }

    @Test
    fun `dismiss never empties the back stack`() {
        val (controller, backStack) = controller(picker)

        controller.dismiss(picker) shouldBe false

        backStack.toList() shouldContainExactly listOf(picker)
    }
}
