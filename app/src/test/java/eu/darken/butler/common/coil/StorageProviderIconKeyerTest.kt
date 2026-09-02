package eu.darken.butler.common.coil

import coil3.request.Options
import coil3.size.Size
import eu.darken.butler.common.storage.saf.StorageProviderApp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageProviderIconKeyerTest : BaseTest() {

    private val keyer = StorageProviderIconKeyer()

    private fun options(sizePx: Int): Options = mockk<Options>().apply {
        every { size } returns Size(sizePx, sizePx)
    }

    private fun app(
        packageName: String = "com.termux",
        lastUpdateTime: Long = 1000L,
    ) = StorageProviderApp(packageName = packageName, appLabel = "Termux", lastUpdateTime = lastUpdateTime)

    @Test
    fun `key is stable for an unchanged app`() {
        keyer.key(app(), options(96)) shouldBe keyer.key(app(), options(96))
    }

    @Test
    fun `key differs per package`() {
        keyer.key(app("com.termux"), options(96)) shouldNotBe keyer.key(app("com.other"), options(96))
    }

    @Test
    fun `key changes when the app is updated`() {
        keyer.key(app(lastUpdateTime = 1000L), options(96)) shouldNotBe keyer.key(app(lastUpdateTime = 2000L), options(96))
    }

    @Test
    fun `key differs per raster size`() {
        keyer.key(app(), options(60)) shouldNotBe keyer.key(app(), options(120))
    }

    @Test
    fun `key never collides with an installed package's key`() {
        keyer.key(app(), options(96)) shouldStartWith "storage-provider-icon-"
    }
}
