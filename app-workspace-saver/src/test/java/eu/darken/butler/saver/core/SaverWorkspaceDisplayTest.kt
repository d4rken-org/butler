package eu.darken.butler.saver.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a single shared file uses the generic save title`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(sourceUris = listOf("content://provider/file")),
        )

        display!!.title!!.get(context) shouldBe context.getString(R.string.saver_workspace_title)
    }

    @Test
    fun `a batch is named by its file count`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/a", "content://provider/b", "content://provider/c"),
            ),
        )

        display!!.title!!.get(context) shouldBe
            context.resources.getQuantityString(R.plurals.saver_workspace_title_count, 3, 3)
    }

    @Test
    fun `zero sources still name the tab`() {
        val display = deriveSaverDisplay(SaverArguments.Default(sourceUris = emptyList()))

        display!!.title!!.get(context) shouldBe context.getString(R.string.saver_workspace_title)
        display.subtitle shouldBe null
    }

    @Test
    fun `the destination describes the tab`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/file"),
                destinationPath = LocalPath.build("/sdcard/Download"),
            ),
        )

        display!!.subtitle!!.get(context) shouldBe "Download"
    }

    @Test
    fun `a known caller describes the tab when there is no destination`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/file"),
                callerPackage = "com.example.app".toPkgId(),
            ),
        )

        display!!.subtitle!!.get(context) shouldBe "com.example.app"
    }

    @Test
    fun `an unknown caller is not shown`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/file"),
                callerPackage = "com.android.shell".toPkgId(),
            ),
        )

        display!!.subtitle shouldBe null
    }

    @Test
    fun `the destination wins over the caller`() {
        val display = deriveSaverDisplay(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/a", "content://provider/b"),
                callerPackage = "com.example.app".toPkgId(),
                destinationPath = LocalPath.build("/sdcard/Download"),
            ),
        )

        display!!.subtitle!!.get(context) shouldBe "Download"
    }
}
