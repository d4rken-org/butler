package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.explorer.core.SortSettings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class EffectiveSortResolverTest : BaseTest() {

    private val globalDefault = SortSettings(mode = SortSettings.Mode.NAME, reversed = false)
    private val bySize = SortSettings(mode = SortSettings.Mode.SIZE, reversed = false)
    private val byModified = SortSettings(mode = SortSettings.Mode.MODIFIED_AT, reversed = true)
    private val tabDefault = SortSettings(mode = SortSettings.Mode.CREATED_AT, reversed = false)

    /** self, parent, grandparent */
    private val keys = listOf("local/a/b/c", "local/a/b", "local/a")

    private fun rule(
        settings: SortSettings?,
        subtree: Boolean = false,
        path: String? = null,
    ) = SortRuleCandidate(settings = settings, subtree = subtree, path = path)

    private fun resolve(
        tabRules: Map<String, SortRuleCandidate> = emptyMap(),
        savedRules: Map<String, SortRuleCandidate> = emptyMap(),
        tabDefault: SortSettings? = null,
    ) = EffectiveSortResolver.resolve(
        ancestorKeys = keys,
        tabRules = tabRules,
        savedRules = savedRules,
        tabDefault = tabDefault,
        globalDefault = globalDefault,
    )

    @Test
    fun `nothing matching falls back to the global default`() {
        val result = resolve()

        result.settings shouldBe globalDefault
        result.winnerKey shouldBe null
        result.winnerIndex shouldBe null
        result.winnerLayer shouldBe null
    }

    @Test
    fun `nothing matching prefers the tab default over the global one`() {
        resolve(tabDefault = tabDefault).settings shouldBe tabDefault
    }

    @Test
    fun `a rule on the folder itself wins`() {
        val result = resolve(savedRules = mapOf(keys[0] to rule(bySize)))

        result.settings shouldBe bySize
        result.winnerIndex shouldBe 0
        result.winnerLayer shouldBe SortRuleLayer.SAVED
    }

    @Test
    fun `an exact ancestor rule does not reach down`() {
        resolve(savedRules = mapOf(keys[1] to rule(bySize, subtree = false))).winnerKey shouldBe null
    }

    @Test
    fun `a subtree ancestor rule reaches down`() {
        val result = resolve(savedRules = mapOf(keys[1] to rule(bySize, subtree = true, path = "/a/b")))

        result.settings shouldBe bySize
        result.winnerIndex shouldBe 1
        result.winnerSubtree shouldBe true
        result.winnerPath shouldBe "/a/b"
    }

    @Test
    fun `the nearest subtree ancestor wins`() {
        val result = resolve(
            savedRules = mapOf(
                keys[1] to rule(bySize, subtree = true),
                keys[2] to rule(byModified, subtree = true),
            )
        )

        result.settings shouldBe bySize
        result.winnerIndex shouldBe 1
    }

    /** Specificity is compared before the layer. */
    @Test
    fun `a nearer saved rule beats a farther tab rule`() {
        val result = resolve(
            tabRules = mapOf(keys[2] to rule(byModified, subtree = true)),
            savedRules = mapOf(keys[1] to rule(bySize, subtree = true)),
        )

        result.settings shouldBe bySize
        result.winnerLayer shouldBe SortRuleLayer.SAVED
        result.winnerIndex shouldBe 1
    }

    @Test
    fun `the tab layer wins only at the same key`() {
        val result = resolve(
            tabRules = mapOf(keys[1] to rule(byModified, subtree = true)),
            savedRules = mapOf(keys[1] to rule(bySize, subtree = true)),
        )

        result.settings shouldBe byModified
        result.winnerLayer shouldBe SortRuleLayer.TAB
        result.winnerIndex shouldBe 1
    }

    @Test
    fun `a tab rule on the folder itself beats a saved subtree ancestor`() {
        val result = resolve(
            tabRules = mapOf(keys[0] to rule(byModified)),
            savedRules = mapOf(keys[1] to rule(bySize, subtree = true)),
        )

        result.settings shouldBe byModified
        result.winnerLayer shouldBe SortRuleLayer.TAB
        result.winnerIndex shouldBe 0
    }

    /**
     * "Use the default here" suppresses rules at and above the folder, but NOT the tab's own default.
     * Landing on the next ancestor instead would make the marker a no-op wherever it is needed.
     */
    @Test
    fun `a follow-default marker lands on the tab default, not the next ancestor`() {
        val result = resolve(
            savedRules = mapOf(
                keys[0] to rule(null),
                keys[1] to rule(bySize, subtree = true, path = "/a/b"),
            ),
            tabDefault = tabDefault,
        )

        result.settings shouldBe tabDefault
        result.ownsFollowDefault shouldBe true
        result.suppressedAncestorPath shouldBe "/a/b"
    }

    @Test
    fun `a follow-default marker without a tab default lands on the global default`() {
        val result = resolve(
            savedRules = mapOf(
                keys[0] to rule(null),
                keys[2] to rule(bySize, subtree = true),
            )
        )

        result.settings shouldBe globalDefault
        result.ownsFollowDefault shouldBe true
    }

    @Test
    fun `a tab-local follow-default marker behaves like the persistent one`() {
        val result = resolve(
            tabRules = mapOf(keys[0] to rule(null)),
            savedRules = mapOf(keys[0] to rule(bySize)),
            tabDefault = tabDefault,
        )

        result.settings shouldBe tabDefault
        result.winnerLayer shouldBe SortRuleLayer.TAB
        result.ownsFollowDefault shouldBe true
    }

    @Test
    fun `a marker owned by an ancestor is not reported as owned here`() {
        val result = resolve(savedRules = mapOf(keys[1] to rule(null, subtree = true)))

        result.settings shouldBe globalDefault
        result.winnerIndex shouldBe 1
        result.ownsFollowDefault shouldBe false
    }

    @Test
    fun `the suppressed ancestor is the nearest rule the winner hides`() {
        val result = resolve(
            savedRules = mapOf(
                keys[0] to rule(bySize, path = "/a/b/c"),
                keys[1] to rule(byModified, subtree = true, path = "/a/b"),
                keys[2] to rule(byModified, subtree = true, path = "/a"),
            )
        )

        result.winnerPath shouldBe "/a/b/c"
        result.suppressedAncestorPath shouldBe "/a/b"
    }

    @Test
    fun `an exact ancestor rule is not counted as a suppressed one`() {
        val result = resolve(
            savedRules = mapOf(
                keys[0] to rule(bySize),
                keys[1] to rule(byModified, subtree = false, path = "/a/b"),
            )
        )

        result.suppressedAncestorPath shouldBe null
    }
}
