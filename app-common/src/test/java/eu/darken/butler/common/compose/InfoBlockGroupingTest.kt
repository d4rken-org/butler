package eu.darken.butler.common.compose

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class InfoBlockGroupingTest : BaseTest() {

    private fun pairable(label: String) = InfoEntry(label = label, value = label, pairable = true)

    private fun solo(label: String) = InfoEntry(label = label, value = label, pairable = false)

    @Test
    fun `nothing to group`() {
        groupInfoEntries(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `consecutive pairable entries go two per row`() {
        val size = pairable("Size")
        val modified = pairable("Modified")
        val created = pairable("Created")
        val format = pairable("Format")

        groupInfoEntries(listOf(size, modified, created, format)) shouldBe listOf(
            listOf(size, modified),
            listOf(created, format),
        )
    }

    @Test
    fun `an odd trailing pairable entry stays alone`() {
        val size = pairable("Size")
        val modified = pairable("Modified")
        val created = pairable("Created")

        groupInfoEntries(listOf(size, modified, created)) shouldBe listOf(
            listOf(size, modified),
            listOf(created),
        )
    }

    @Test
    fun `a wider grid packs more pairable entries per row`() {
        val size = pairable("Size")
        val modified = pairable("Modified")
        val created = pairable("Created")
        val format = pairable("Format")

        groupInfoEntries(listOf(size, modified, created, format), columns = 3) shouldBe listOf(
            listOf(size, modified, created),
            listOf(format),
        )
    }

    @Test
    fun `a single column grid gives every entry its own row`() {
        val size = pairable("Size")
        val modified = pairable("Modified")

        groupInfoEntries(listOf(size, modified), columns = 1) shouldBe listOf(
            listOf(size),
            listOf(modified),
        )
    }

    @Test
    fun `a column count below one is treated as one`() {
        val size = pairable("Size")

        groupInfoEntries(listOf(size), columns = 0) shouldBe listOf(listOf(size))
    }

    @Test
    fun `columns follow the width available for them`() {
        // The two narrowest cards that pair today: one in a floating bar on a 320dp pane (320
        // less two 16dp bar insets and two 12dp card insets), one in page content on a 360dp
        // phone (360 less two 12dp content insets and two 12dp card insets).
        infoGridColumns(264.dp) shouldBe 2
        infoGridColumns(312.dp) shouldBe 2
        infoGridColumns(700.dp) shouldBe 5
        infoGridColumns(1150.dp) shouldBe 8
    }

    @Test
    fun `a grid too narrow for one full column still gets one`() {
        infoGridColumns(60.dp) shouldBe 1
        infoGridColumns(0.dp) shouldBe 1
        infoGridColumns((-20).dp) shouldBe 1
    }

    @Test
    fun `a narrower minimum column width fits more columns`() {
        // The operation overview pairs from 240dp on, which is what its own minimum encodes.
        infoGridColumns(240.dp, minColumnWidth = 114.dp) shouldBe 2
        infoGridColumns(239.dp, minColumnWidth = 114.dp) shouldBe 1
    }

    @Test
    fun `a non pairable entry always gets its own row`() {
        val size = pairable("Size")
        val permissions = solo("Permissions")
        val owner = solo("Owner")
        val modified = pairable("Modified")

        groupInfoEntries(listOf(size, permissions, owner, modified)) shouldBe listOf(
            listOf(size),
            listOf(permissions),
            listOf(owner),
            listOf(modified),
        )
    }
}
