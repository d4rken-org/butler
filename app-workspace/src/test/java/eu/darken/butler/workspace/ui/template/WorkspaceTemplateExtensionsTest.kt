package eu.darken.butler.workspace.ui.template

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceTemplateExtensionsTest : BaseTest() {

    private class FakeTemplate(
        override val type: Workspace.Type,
        override val sortOrder: Int,
        override val isQuickCreate: Boolean = false,
        override val availability: Flow<Boolean> = flowOf(true),
    ) : WorkspaceTemplate {
        override val icon: ImageVector = Icons.TwoTone.Workspaces
        override val title: CaString = type.name.toCaString()
        override val subtitle: CaString = type.name.toCaString()
        override val arguments: Workspace.Arguments = TemplatesArguments.Default()
    }

    @Test
    fun `empty collection emits empty list`() = runTest {
        emptyList<WorkspaceTemplate>().availableTemplates().first() shouldBe emptyList()
    }

    @Test
    fun `templates are sorted by sortOrder with type ordinal tie-break`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.HISTORY, sortOrder = 50),
            FakeTemplate(Workspace.Type.DEVELOPER, sortOrder = 100),
            FakeTemplate(Workspace.Type.SEARCHER, sortOrder = 20),
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.APPS, sortOrder = 20),
        )

        val result = templates.availableTemplates().first().map { it.type }

        result shouldContainExactly listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.SEARCHER, // sortOrder 20, ordinal beats APPS
            Workspace.Type.APPS,
            Workspace.Type.HISTORY,
            Workspace.Type.DEVELOPER,
        )
    }

    @Test
    fun `unavailable templates are filtered and reappear reactively`() = runTest {
        val devModeUnlocked = MutableStateFlow(false)
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.DEVELOPER, sortOrder = 100, availability = devModeUnlocked),
        )

        val flow = templates.availableTemplates()

        flow.first().map { it.type } shouldContainExactly listOf(Workspace.Type.EXPLORER)

        devModeUnlocked.value = true
        flow.first().map { it.type } shouldContainExactly listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.DEVELOPER,
        )
    }

    @Test
    fun `availability errors exclude the template instead of breaking the flow`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(
                Workspace.Type.DEVELOPER,
                sortOrder = 100,
                availability = flow { throw IllegalStateException("boom") },
            ),
        )

        templates.availableTemplates().first().map { it.type } shouldContainExactly
            listOf(Workspace.Type.EXPLORER)
    }

    @Test
    fun `quick create selection respects flag order and template arguments`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.HISTORY, sortOrder = 50, isQuickCreate = false),
            FakeTemplate(Workspace.Type.APPS, sortOrder = 40, isQuickCreate = true),
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10, isQuickCreate = true),
        )

        val items = templates.availableTemplates().first()
            .filter { it.isQuickCreate }
            .map { it.toQuickCreateItem() }

        items.map { it.type } shouldContainExactly listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.APPS,
        )
        items.first().arguments shouldBe templates.first { it.type == Workspace.Type.EXPLORER }.arguments
    }
}
