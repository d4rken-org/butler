package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Document.*
import eu.darken.butler.common.ca.CaString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ConnectionTest : BaseTest() {

    @Test
    fun `Device home has title from string resource`() {
        ProviderLocation.Home.Device.title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device home has summary from string resource`() {
        ProviderLocation.Home.Device.summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device home wire format is stable`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val device = ProviderLocation.Home.Device

        device.documentId shouldBe "device|self"
        device.icon shouldBe eu.darken.butler.provider.documents.R.drawable.devices_24px
        device.flags shouldBe FLAG_DIR_SUPPORTS_CREATE
    }
}
