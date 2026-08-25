package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OperationPathPlanTest : BaseTest() {

    private val source = LocalPath.build("/sdcard/Download/a.txt")
    private val second = LocalPath.build("/sdcard/Download/b.txt")
    private val folder = LocalPath.build("/sdcard/Backup")

    @Test
    fun `scope paths default to the targets plus the destination`() {
        val plan = OperationPathPlan(
            targets = listOf(source, second),
            destination = OperationPathPlan.Destination.Container(folder),
        )

        plan.scopePaths shouldContainExactly listOf(source, second, folder)
        plan.allPaths shouldContainExactly plan.scopePaths
    }

    @Test
    fun `scope paths without a destination are just the targets`() {
        OperationPathPlan(targets = listOf(source)).scopePaths shouldContainExactly listOf(source)
    }

    @Test
    fun `the destination appears once in the path set`() {
        val plan = OperationPathPlan(
            targets = listOf(source),
            destination = OperationPathPlan.Destination.RequestedTarget(folder),
        )

        plan.allPaths.count { it == folder } shouldBe 1
    }

    @Test
    fun `an explicit scope override wins over the default`() {
        val plan = OperationPathPlan(
            targets = listOf(source, second),
            destination = OperationPathPlan.Destination.Container(folder),
            scopePaths = listOf(source),
        )

        plan.scopePaths shouldContainExactly listOf(source)
        plan.allPaths shouldContainExactly listOf(source)
    }

    @Test
    fun `the representative path is the first target`() {
        val plan = OperationPathPlan(
            targets = listOf(source, second),
            destination = OperationPathPlan.Destination.Container(folder),
        )

        plan.representativePath shouldBe source
    }

    @Test
    fun `the representative path falls back to the destination without targets`() {
        val plan = OperationPathPlan(
            targets = emptyList(),
            destination = OperationPathPlan.Destination.Container(folder),
        )

        plan.representativePath shouldBe folder
    }

    @Test
    fun `an empty plan degrades gracefully instead of throwing`() {
        val plan = OperationPathPlan(targets = emptyList())

        plan.representativePath shouldBe null
        plan.allPaths shouldContainExactly emptyList()
    }

    @Test
    fun `an explicit representative override wins over the default`() {
        val plan = OperationPathPlan(
            targets = listOf(source, second),
            representativePath = second,
        )

        plan.representativePath shouldBe second
    }
}
