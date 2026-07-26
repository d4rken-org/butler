package eu.darken.butler.apps.core.details.components

import android.content.pm.PackageManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ComponentEnabledStateTest : BaseTest() {

    @Test
    fun `an explicit component override wins over the manifest`() {
        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            appEnabled = true,
            manifestEnabled = false,
        ) shouldBe true

        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            appEnabled = true,
            manifestEnabled = true,
        ) shouldBe false

        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            appEnabled = true,
            manifestEnabled = true,
        ) shouldBe false

        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            appEnabled = true,
            manifestEnabled = true,
        ) shouldBe false
    }

    @Test
    fun `the default setting defers to the manifest baseline`() {
        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            appEnabled = true,
            manifestEnabled = true,
        ) shouldBe true

        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            appEnabled = true,
            manifestEnabled = false,
        ) shouldBe false
    }

    @Test
    fun `a disabled application forces disabled even for an enabled component`() {
        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            appEnabled = false,
            manifestEnabled = true,
        ) shouldBe false

        resolveEnabled(
            componentSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            appEnabled = false,
            manifestEnabled = true,
        ) shouldBe false
    }
}
