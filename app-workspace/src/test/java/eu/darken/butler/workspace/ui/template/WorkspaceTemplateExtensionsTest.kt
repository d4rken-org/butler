package eu.darken.butler.workspace.ui.template

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceTemplateExtensionsTest : BaseTest() {

    /** String resources resolve to a stable stand-in so labels can be compared without a device. */
    private val context = mockk<Context>().apply {
        every { getString(any<Int>()) } answers { "res-${firstArg<Int>()}" }
    }

    private class FakeTemplate(
        override val type: Workspace.Type,
        override val sortOrder: Int,
        override val isQuickCreate: Boolean = false,
        override val availability: Flow<Boolean> = flowOf(true),
        override val title: CaString = type.name.toCaString(),
    ) : WorkspaceTemplate {
        override val icon: ImageVector = Icons.TwoTone.Workspaces
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
    fun `new tab resolves the stored type to its template`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.SEARCHER, sortOrder = 20),
        )

        val available = templates.availableTemplates().first()

        available.newTabTemplate(Workspace.Type.EXPLORER)?.type shouldBe Workspace.Type.EXPLORER
        available.newTabTemplate(Workspace.Type.SEARCHER)?.type shouldBe Workspace.Type.SEARCHER
    }

    @Test
    fun `new tab falls back to the picker without a resolvable template`() = runTest {
        val templates = listOf(FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10))

        val available = templates.availableTemplates().first()

        available.newTabTemplate(Workspace.Type.TEMPLATES) shouldBe null
        available.newTabTemplate(Workspace.Type.EDITOR) shouldBe null
        emptyList<WorkspaceTemplate>().newTabTemplate(Workspace.Type.EXPLORER) shouldBe null
    }

    @Test
    fun `new tab never resolves to a singleton type`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.DEVELOPER, sortOrder = 100),
        )

        templates.availableTemplates().first().newTabTemplate(Workspace.Type.DEVELOPER) shouldBe null
    }

    @Test
    fun `new tab type name uses the wording the picker offered`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10, title = "Explorer".toCaString()),
            // The template's own wording, which differs from Workspace.Type.SEARCHER.label.
            FakeTemplate(Workspace.Type.SEARCHER, sortOrder = 20, title = "Search".toCaString()),
        )

        val available = templates.availableTemplates().first()

        available.newTabTypeName(Workspace.Type.SEARCHER).get(context) shouldBe "Search"
        available.newTabTypeName(Workspace.Type.EXPLORER).get(context) shouldBe "Explorer"
    }

    @Test
    fun `new tab type name falls back to the workspace label without a template`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.DEVELOPER, sortOrder = 100, title = "Developer Tools".toCaString()),
        )

        val available = templates.availableTemplates().first()

        available.newTabTypeName(Workspace.Type.TEMPLATES).get(context) shouldBe
            Workspace.Type.TEMPLATES.label.get(context)
        available.newTabTypeName(Workspace.Type.EDITOR).get(context) shouldBe
            Workspace.Type.EDITOR.label.get(context)
        // Singletons are not new tab candidates, so their template title never applies.
        available.newTabTypeName(Workspace.Type.DEVELOPER).get(context) shouldBe
            Workspace.Type.DEVELOPER.label.get(context)
    }

    @Test
    fun `new tab candidates drop singletons and keep the remaining order`() = runTest {
        val templates = listOf(
            FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10),
            FakeTemplate(Workspace.Type.DEVELOPER, sortOrder = 20),
            FakeTemplate(Workspace.Type.SEARCHER, sortOrder = 30),
            FakeTemplate(Workspace.Type.BUG_REPORT, sortOrder = 40),
            FakeTemplate(Workspace.Type.HISTORY, sortOrder = 50),
        )

        templates.availableTemplates().first().newTabCandidates().map { it.type } shouldContainExactly listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.SEARCHER,
            Workspace.Type.HISTORY,
        )
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
