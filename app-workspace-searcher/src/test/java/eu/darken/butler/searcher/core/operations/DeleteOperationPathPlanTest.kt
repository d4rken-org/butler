package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import testhelpers.BaseTest

class DeleteOperationPathPlanTest : BaseTest() {

    private val first = LocalPath.build("/sdcard/Download/a.txt")
    private val second = LocalPath.build("/sdcard/Download/b.txt")

    @Test
    fun `a delete targets the paths and has no destination`() {
        val plan = DeleteOperation(
            workspaceId = Workspace.Id(),
            command = SearcherCommand.Delete(targets = setOf(first, second)),
            issueHandler = mockk(),
            coreDeleteExecutor = mockk(),
            fileSystemHinter = mockk(),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(first, second)
        plan.destination shouldBe null
        plan.scopePaths shouldContainExactly listOf(first, second)
        plan.representativePath shouldBe first
    }
}
