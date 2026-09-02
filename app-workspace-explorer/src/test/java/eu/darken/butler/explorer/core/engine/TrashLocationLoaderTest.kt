package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * "Folder contents no longer available" is a statement about the folder, so only a definitive
 * absence may produce it. A folder that cannot be inspected has to keep the listing's own error.
 */
class TrashLocationLoaderTest : BaseTest() {

    private val gatewaySwitch = mockk<GatewaySwitch>(relaxed = true)
    private val trashRepo = mockk<TrashRepo>()

    private val trashPath = LocalPath.build("/storage/emulated/0/.butler-trash/folder")

    private val parentRef = TrashItemReference(
        itemId = Uuid.random(),
        displayName = "folder".toCaString(),
        originalPath = LocalPath.build("/storage/emulated/0/Documents/folder"),
        trashPath = trashPath,
        deletedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun loader() = TrashLocationLoader(
        workspaceId = Workspace.Id(),
        trashRepo = trashRepo,
        gatewaySwitch = gatewaySwitch,
        metadataRepo = mockk(relaxed = true),
    )

    init {
        coEvery { gatewaySwitch.useRes<Any?>(any()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { trashRepo.getById(any()) } returns mockk(relaxed = true)
    }

    @Test
    fun `a folder that cannot be inspected surfaces the listing error`() = runTest {
        val original = IOException("gateway gave up")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN
        coEvery { gatewaySwitch.lookupFiles(any(), any<LookupOptions>()) } throws original

        shouldThrow<IOException> { loader().loadNested(parentRef, "").toList() } shouldBe original
    }

    @Test
    fun `a folder that is provably gone is reported as unavailable without listing it`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        val error = shouldThrow<IllegalStateException> { loader().loadNested(parentRef, "").toList() }

        error.message shouldBe "Folder contents no longer available"
        coVerify(exactly = 0) { gatewaySwitch.lookupFiles(any(), any<LookupOptions>()) }
    }
}
