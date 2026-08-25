package eu.darken.butler.viewer.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import testhelpers.BaseTest

class DeleteOperationPathPlanTest : BaseTest() {

    private val target = LocalPath.build("/sdcard/Download/a.txt")

    @Test
    fun `a delete targets the path and has no destination`() {
        val plan = DeleteOperation(
            workspaceId = Workspace.Id(),
            command = ViewerCommand.Delete(targets = setOf(target)),
            issueHandler = mockk(),
            coreDeleteExecutor = mockk(),
            fileSystemHinter = mockk(),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(target)
        plan.destination shouldBe null
        plan.scopePaths shouldContainExactly listOf(target)
        plan.representativePath shouldBe target
    }
}
