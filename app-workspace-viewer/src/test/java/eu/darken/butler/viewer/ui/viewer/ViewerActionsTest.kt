package eu.darken.butler.viewer.ui.viewer

import androidx.core.net.toUri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ViewerActionsTest : BaseTest() {

    private val stored = ViewerSource.Stored(LocalPath.build("/storage/emulated/0/Download/app.apk"))
    private val streamed = ViewerSource.Streamed(
        uri = "content://com.example.files/document/42".toUri(),
        displayName = "app.apk",
        mime = MimeInfo("application/vnd.android.package-archive"),
        sizeBytes = 1024L,
        arrivalId = "arrival-1",
    )
    private val apkInfo = ApkArchiveInfo(id = "com.example.app".toPkgId(), versionCode = 1L)

    private val apk = ViewerContent.Apk(
        mime = MimeInfo("application/vnd.android.package-archive"),
        apkInfo = apkInfo,
        installState = ApkInstallState.NotInstalled,
    )
    private val bundle = ViewerContent.AppBundle(
        mime = MimeInfo("application/zip"),
        format = AppInstallFormat.XAPK,
        apkInfo = apkInfo,
        splitCount = 3,
        hasObb = true,
        needsElevationForObb = true,
        installState = ApkInstallState.NotInstalled,
    )

    @Test
    fun `install leads for an apk`() {
        viewerActions(stored, trashEnabled = false, content = apk)
            .first() shouldBe ViewerActionBarItem.Install
    }

    @Test
    fun `a bundle leads with install and keeps the browse offer`() {
        val actions = viewerActions(stored, trashEnabled = false, content = bundle)

        actions.first() shouldBe ViewerActionBarItem.Install
        // A bundle is a browsable zip, and tapping it no longer browses, so the offer stays here.
        actions[1] shouldBe ViewerActionBarItem.BrowseArchive
    }

    @Test
    fun `install is not offered for anything else`() {
        val archive = ViewerContent.Archive(
            mime = MimeInfo("application/zip"),
            format = ArchiveFormat.ZIP,
            access = ViewerContent.Archive.Access.BROWSABLE,
        )

        viewerActions(stored, trashEnabled = false, content = archive)
            .contains(ViewerActionBarItem.Install) shouldBe false
        viewerActions(stored, trashEnabled = false, content = ViewerContent.Image(MimeInfo("image/jpeg")))
            .contains(ViewerActionBarItem.Install) shouldBe false
    }

    @Test
    fun `a file that is gone cannot be installed`() {
        viewerActions(stored, trashEnabled = false, content = apk, isGone = true)
            .contains(ViewerActionBarItem.Install) shouldBe false
        viewerActions(stored, trashEnabled = false, content = bundle, isGone = true)
            .contains(ViewerActionBarItem.Install) shouldBe false
    }

    @Test
    fun `streamed content keeps its single action`() {
        viewerActions(streamed, trashEnabled = false, content = apk) shouldBe
            listOf(ViewerActionBarItem.SaveCopy)
    }
}
