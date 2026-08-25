package eu.darken.butler.common.compose

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
