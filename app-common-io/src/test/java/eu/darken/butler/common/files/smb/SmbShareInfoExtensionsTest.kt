package eu.darken.butler.common.files.smb

import eu.darken.smb.SmbShareInfo
import eu.darken.smb.SmbShareInfo.Type
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SmbShareInfoExtensionsTest : BaseTest() {

    private fun share(name: String, type: Type = Type.DISK, special: Boolean = false) =
        SmbShareInfo(name = name, type = type, special = special, comment = "")

    @Test
    fun `a plain disk share is browsable`() {
        share("media").isBrowsable() shouldBe true
        share("Family Photos").isBrowsable() shouldBe true
    }

    @Test
    fun `non-disk shares are not browsable`() {
        share("IPC$", Type.IPC, special = true).isBrowsable() shouldBe false
        share("laser", Type.PRINTER).isBrowsable() shouldBe false
        share("com1", Type.DEVICE).isBrowsable() shouldBe false
        share("odd", Type.OTHER).isBrowsable() shouldBe false
    }

    @Test
    fun `special shares are not browsable`() {
        share("C$", special = true).isBrowsable() shouldBe false
        share("media", special = true).isBrowsable() shouldBe false
    }

    @Test
    fun `dollar shares are hidden even when the server does not flag them`() {
        share("backup$").isBrowsable() shouldBe false
        share("price\$list").isBrowsable() shouldBe true
    }

    @Test
    fun `names that cannot be a path segment are not browsable`() {
        share("").isBrowsable() shouldBe false
        share(" ").isBrowsable() shouldBe false
        share(".").isBrowsable() shouldBe false
        share("..").isBrowsable() shouldBe false
        share("a/b").isBrowsable() shouldBe false
        share("a\\b").isBrowsable() shouldBe false
    }
}
