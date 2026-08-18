package eu.darken.butler.viewer.ui.viewer

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FileInfoCardGroupingTest : BaseTest() {

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
    fun `permissions only share a row where the column can hold the whole value`() {
        // A full-width card on a phone, and a landscape tablet pane: room for both halves.
        permissionsFitHalfWidth(360.dp) shouldBe true
        permissionsFitHalfWidth(PairedPermissionsMinCardWidth) shouldBe true

        // A 360dp phone the user forced into DUAL_VERTICAL portrait: the octal would ellipsize away.
        permissionsFitHalfWidth(180.dp) shouldBe false
    }

    @Test
    fun `everything collapses to the first row`() {
        val rows = groupInfoEntries(
            listOf(pairable("Size"), pairable("Modified"), pairable("Permissions"), solo("Owner")),
        )

        visibleInfoRows(rows, expanded = true) shouldBe rows
        visibleInfoRows(rows, expanded = false) shouldBe listOf(rows.first())
    }

    @Test
    fun `a single row card has nothing to collapse away`() {
        val rows = groupInfoEntries(listOf(pairable("Size")))

        visibleInfoRows(rows, expanded = false) shouldBe rows
    }

    @Test
    fun `collapsing an empty card yields nothing`() {
        visibleInfoRows(emptyList(), expanded = false) shouldBe emptyList()
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
