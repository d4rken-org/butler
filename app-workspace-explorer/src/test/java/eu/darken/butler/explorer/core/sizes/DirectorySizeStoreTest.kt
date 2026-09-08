package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.sorting.rules.TabSortRule
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class DirectorySizeStoreTest : BaseTest() {

    private val scannedAt = Instant.parse("2026-09-07T14:32:00Z")

    private fun path(value: String): APath<*> = LocalPath.build(value)

    private fun scan(root: String, vararg sizes: Pair<String, Long>) = DirectoryScan(
        root = path(root),
        scannedAt = scannedAt,
        sizes = sizes.associate { (key, bytes) -> key to DirectorySize(bytes, true) },
        itemCount = sizes.size.toLong(),
        errorCount = 0,
    )

    @Test
    fun `a scan covers its root and everything below it`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L, "/a/b" to 4L)) shouldBe true

        store.snapshot.value.scanFor(path("/a")).shouldNotBeNull().root.path shouldBe "/a"
        store.snapshot.value.scanFor(path("/a/b")).shouldNotBeNull().root.path shouldBe "/a"
        store.snapshot.value.scanFor(path("/other")) shouldBe null
    }

    @Test
    fun `the deepest scan wins when two nest`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L))
        store.publish(scan("/a/b/c", "/a/b/c" to 4L))

        store.snapshot.value.scanFor(path("/a/b/c/d")).shouldNotBeNull().root.path shouldBe "/a/b/c"
    }

    @Test
    fun `a fresh parent scan retires the scans it covers`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a/b", "/a/b" to 4L))
        store.publish(scan("/a", "/a" to 10L))

        store.snapshot.value.scans.keys shouldBe setOf("/a")
    }

    @Test
    fun `a change below a scanned root drops that scan and only that one`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L))
        store.publish(scan("/b", "/b" to 20L))

        store.invalidate(listOf(path("/a/b/file")))

        store.snapshot.value.scans.keys shouldBe setOf("/b")
    }

    @Test
    fun `a change above a scanned root drops it too`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a/b/c", "/a/b/c" to 10L))

        store.invalidate(listOf(path("/a/b")))

        store.snapshot.value.scans.keys shouldBe emptySet()
    }

    @Test
    fun `an unrelated change drops nothing`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L))

        store.invalidate(listOf(path("/elsewhere/file")))

        store.snapshot.value.scans.keys shouldBe setOf("/a")
    }

    @Test
    fun `a root can only be reserved once at a time`() {
        val store = DirectorySizeStore()

        store.markRunning(path("/a")) shouldBe true
        store.markRunning(path("/a")) shouldBe false
        store.snapshot.value.isRunning(path("/a")) shouldBe true

        store.markFinished(path("/a"))

        store.snapshot.value.isRunning(path("/a")) shouldBe false
        store.markRunning(path("/a")) shouldBe true
    }

    @Test
    fun `a scan invalidated while it ran is not published`() {
        val store = DirectorySizeStore()
        store.markRunning(path("/a")) shouldBe true

        store.invalidate(listOf(path("/a/x")))
        store.publish(scan("/a", "/a" to 10L)) shouldBe false

        store.snapshot.value.scanFor(path("/a")) shouldBe null

        store.markFinished(path("/a"))
        store.snapshot.value.stale shouldBe emptySet()
    }

    @Test
    fun `the first sort switch recorded for a root is the one that is kept`() {
        val store = DirectorySizeStore()
        val previous = TabSortRule(
            settings = SortSettings(mode = SortSettings.Mode.NAME),
            subtree = false,
            path = "serialized",
        )

        store.recordSortSwitch(path("/a"), previous)
        store.recordSortSwitch(path("/a"), null)

        store.snapshot.value.sortSwitches shouldBe mapOf("/a" to DirectorySizeStore.SortRestore(previous))
    }

    @Test
    fun `discarding a scan returns its sort switch and forgets both`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L))
        store.recordSortSwitch(path("/a"), null)

        store.discard(path("/a")) shouldBe DirectorySizeStore.SortRestore(null)

        store.snapshot.value.scans.keys shouldBe emptySet()
        store.snapshot.value.sortSwitches.keys shouldBe emptySet()
    }

    @Test
    fun `a change that drops a scan drops its sort switch with it`() {
        val store = DirectorySizeStore()
        store.publish(scan("/a", "/a" to 10L))
        store.recordSortSwitch(path("/a"), null)

        store.invalidate(listOf(path("/a/b/file")))

        store.snapshot.value.scans.keys shouldBe emptySet()
        store.snapshot.value.sortSwitches.keys shouldBe emptySet()
    }
}
