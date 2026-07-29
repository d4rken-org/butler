package eu.darken.butler.common.pkgs.pkgops

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PkgOpsSizeStatsTest : BaseTest() {

    @Test
    fun `total does not count the cache twice`() {
        val stats = PkgOps.SizeStats(
            appBytes = 100L,
            cacheBytes = 20L,
            externalCacheBytes = 5L,
            dataBytes = 50L,
        )

        stats.total shouldBe 150L
    }

    @Test
    fun `total ignores the external cache`() {
        val stats = PkgOps.SizeStats(
            appBytes = 1L,
            cacheBytes = 0L,
            externalCacheBytes = 999L,
            dataBytes = 2L,
        )

        stats.total shouldBe 3L
    }
}
