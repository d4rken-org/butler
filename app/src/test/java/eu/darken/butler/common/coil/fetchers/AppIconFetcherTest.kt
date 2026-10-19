package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import coil3.decode.DataSource
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caDrawable
import eu.darken.butler.common.funnel.IPCFunnel
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/** MEMORY is what TintedAsyncImage tints, DISK is drawn as-is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppIconFetcherTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val funnel = IPCFunnel(context, TestDispatcherProvider())

    private class InstalledPkg(private val drawable: Drawable?) : Installed {
        override val packageInfo = PackageInfo().apply { packageName = "com.not.on.this.device" }
        override val userHandle = UserHandle2(0)
        override val label: CaString? = null
        override val icon: CaDrawable? = drawable?.let { d -> caDrawable { d } }
    }

    private class OtherPkg(private val drawable: Drawable?) : Pkg {
        override val id = Pkg.Id("com.not.on.this.device")
        override val label: CaString? = null
        override val icon: CaDrawable? = drawable?.let { d -> caDrawable { d } }
    }

    private suspend fun dataSourceOf(pkg: Pkg): DataSource =
        (AppIconFetcher(funnel, pkg, Options(context)).fetch() as ImageFetchResult).dataSource

    private fun bitmapDrawable() = BitmapDrawable(context.resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    @Test
    fun `an installed package's fallback glyph is tinted at draw time`() = runTest {
        dataSourceOf(InstalledPkg(ColorDrawable())) shouldBe DataSource.MEMORY
    }

    @Test
    fun `an installed package without any icon gets the tinted default glyph`() = runTest {
        dataSourceOf(InstalledPkg(null)) shouldBe DataSource.MEMORY
    }

    @Test
    fun `other packages keep their own icon untinted`() = runTest {
        dataSourceOf(OtherPkg(bitmapDrawable())) shouldBe DataSource.DISK
    }

    @Test
    fun `other packages without an icon get the tinted default glyph`() = runTest {
        dataSourceOf(OtherPkg(null)) shouldBe DataSource.MEMORY
    }
}
