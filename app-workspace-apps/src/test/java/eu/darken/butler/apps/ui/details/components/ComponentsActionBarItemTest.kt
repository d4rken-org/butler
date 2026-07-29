package eu.darken.butler.apps.ui.details.components

import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ComponentsActionBarItemTest : BaseTest() {

    private fun entry(name: String, state: ComponentEnabledState) = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.$name",
        isExported = false,
        enabledState = state,
    )

    private val enabled = entry("Enabled", ComponentEnabledState.ENABLED)
    private val disabled = entry("Disabled", ComponentEnabledState.DISABLED)
    private val unresolved = entry("Unresolved", ComponentEnabledState.UNRESOLVED)

    @Test
    fun `disable is offered only when every entry is enabled`() {
        ComponentsActionBarItem.Disable(listOf(enabled)).isVisible shouldBe true
        ComponentsActionBarItem.Disable(listOf(enabled, disabled)).isVisible shouldBe false
        ComponentsActionBarItem.Disable(listOf(enabled, unresolved)).isVisible shouldBe false
        ComponentsActionBarItem.Disable(emptyList()).isVisible shouldBe false
    }

    @Test
    fun `enable is offered when at least one entry is disabled`() {
        ComponentsActionBarItem.Enable(listOf(disabled)).isVisible shouldBe true
        ComponentsActionBarItem.Enable(listOf(enabled, disabled)).isVisible shouldBe true
        ComponentsActionBarItem.Enable(listOf(enabled)).isVisible shouldBe false
        ComponentsActionBarItem.Enable(emptyList()).isVisible shouldBe false
    }

    /** A mixed selection has no known direction for the unresolved entries, so nothing is offered. */
    @Test
    fun `enable is not offered for a mixed disabled and unresolved selection`() {
        ComponentsActionBarItem.Enable(listOf(disabled, unresolved)).isVisible shouldBe false
    }
}
