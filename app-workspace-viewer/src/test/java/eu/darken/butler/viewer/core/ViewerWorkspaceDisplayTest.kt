package eu.darken.butler.viewer.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val path = LocalPath.build("/storage/emulated/0/DCIM/Camera/photo.jpg")

    @Test
    fun `a tab is named after the file it shows`() {
        val display = deriveViewerDisplay(ViewerArguments.Default(filePath = path))

        display.title!!.get(context) shouldBe "photo.jpg"
        display.subtitle!!.get(context) shouldBe "/storage/emulated/0/DCIM/Camera"
    }

    @Test
    fun `the live seed matches the derivation`() {
        val arguments = ViewerArguments.Default(filePath = path)
        val gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { useRes(any<suspend (Any) -> Any?>()) } coAnswers {
                firstArg<suspend (Any) -> Any?>().invoke(this@apply)
            }
            @Suppress("UNCHECKED_CAST")
            coEvery { lookup(any(), any()) } coAnswers {
                LocalPathLookup(
                    lookedUp = firstArg<APath<*>>() as LocalPath,
                    fileType = FileType.FILE,
                    size = 1024L,
                    modifiedAt = null,
                ) as APathLookup<APath<*>>
            }
        }
        val imageProbe = mockk<ImageProbe>().apply {
            coEvery { probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
        }

        val workspace = ViewerWorkspace(
            id = Workspace.Id(),
            creationArguments = arguments,
            dispatcherProvider = TestDispatcherProvider(),
            gatewaySwitch = gatewaySwitch,
            contentReader = readerFor(gatewaySwitch),
            imageProbe = imageProbe,
            apkArchiveParser = mockk(relaxed = true),
            appInstallInspector = mockk(relaxed = true),
            pkgRepo = mockk(relaxed = true),
            userManager2 = mockk(relaxed = true),
            pdfPreviewLoader = mockk(relaxed = true),
            textPreviewLoader = mockk(relaxed = true),
            operationsManager = mockk(relaxed = true),
            issueHandler = mockk(relaxed = true),
            deleteOperationFactory = mockk(relaxed = true),
        )
        val derived = deriveViewerDisplay(arguments)

        workspace.info.value.title.get(context) shouldBe derived.title!!.get(context)
        workspace.info.value.subtitle!!.get(context) shouldBe derived.subtitle!!.get(context)
        workspace.info.value.contentPath shouldBe path
    }
}
