package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Document.*
import eu.darken.butler.common.ca.CaString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ConnectionTest : BaseTest() {

    @Test
    fun `Device home has correct documentId`() {
        ProviderLocation.Home.Device.documentId shouldBe "device|self"
    }

    @Test
    fun `Device home documentId matches expected format`() {
        ProviderLocation.Home.Device.documentId shouldBe "device|self"
    }

    @Test
    fun `Device home has FLAG_DIR_SUPPORTS_CREATE flag set`() {
        val flags = ProviderLocation.Home.Device.flags
        (flags and FLAG_DIR_SUPPORTS_CREATE) shouldBe FLAG_DIR_SUPPORTS_CREATE
    }

    @Test
    fun `Device home has valid icon resource ID`() {
        ProviderLocation.Home.Device.icon shouldBe eu.darken.butler.provider.documents.R.drawable.devices_24px
    }

    @Test
    fun `Device home has title from string resource`() {
        val title = ProviderLocation.Home.Device.title
        title.shouldBeInstanceOf<CaString>()
        title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device home has summary from string resource`() {
        val summary = ProviderLocation.Home.Device.summary
        summary shouldNotBe null
        summary.shouldBeInstanceOf<CaString>()
        summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device home is a ProviderLocation Home instance`() {
        val home: ProviderLocation.Home = ProviderLocation.Home.Device
        home.shouldBeInstanceOf<ProviderLocation.Home>()
    }

    @Test
    fun `sealed interface has only Device implementation in Phase 1`() {
        // This test documents that Phase 1 has only one home
        // If future phases add more homes (SSH, FTP), this test will need updating
        val home: ProviderLocation.Home = ProviderLocation.Home.Device
        when (home) {
            ProviderLocation.Home.Device -> {
                // Expected - only implementation in Phase 1
            }
            // No else needed - sealed interface exhaustiveness
        }
    }

    @Test
    fun `Device home properties are stable and unchanging`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val device = ProviderLocation.Home.Device

        device.documentId shouldBe "device|self"
        device.icon shouldBe eu.darken.butler.provider.documents.R.drawable.devices_24px
        device.flags shouldBe FLAG_DIR_SUPPORTS_CREATE
    }
}
