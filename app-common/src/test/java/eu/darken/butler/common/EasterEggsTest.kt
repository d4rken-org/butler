package eu.darken.butler.common

import eu.darken.butler.common.Occasions.Period
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.random.Random

class EasterEggsTest : BaseTest() {

    private val everySeasonal = Period.entries.flatMap { seasonalEggs(it) }.toSet()

    @Test
    fun `every occasion has its own lines`() {
        Period.entries.filter { it != Period.NONE }.forEach { seasonalEggs(it).shouldNotBeEmpty() }
        seasonalEggs(Period.NONE).shouldBeEmpty()
    }

    @Test
    fun `outside an occasion only the general lines are drawn`() {
        val random = Random(42)
        repeat(200) {
            (easterEggProgressMsg(Period.NONE, random) in everySeasonal) shouldBe false
        }
    }

    @Test
    fun `during an occasion its own and the general lines are both drawn`() {
        val random = Random(42)
        val drawn = List(200) { easterEggProgressMsg(Period.HALLOWEEN, random) }

        val (seasonal, general) = drawn.partition { it in everySeasonal }
        seasonal.all { it in seasonalEggs(Period.HALLOWEEN) } shouldBe true
        seasonal.shouldNotBeEmpty()
        general.shouldNotBeEmpty()
    }
}
