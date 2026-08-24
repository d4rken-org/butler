package eu.darken.butler.explorer.core.favorites

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.ExplorerSettings
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExplorerFavoritesRepoTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    private val dispatcherProvider = object : DispatcherProvider {
        override val Default = UnconfinedTestDispatcher()
        override val IO = UnconfinedTestDispatcher()
        override val Main = UnconfinedTestDispatcher()
        override val Unconfined = Dispatchers.Unconfined
    }

    private fun freshRepo(
        scope: CoroutineScope,
        gatewaySwitch: GatewaySwitch = mockk(relaxed = true),
    ): ExplorerFavoritesRepo {
        // Each test gets a unique settings instance so DataStore files don't collide.
        // Robolectric provides a fresh sandbox files dir per test, so the standard
        // settings_explorer file is empty at start.
        val settings = ExplorerSettings(context, json)
        return ExplorerFavoritesRepo(
            appScope = scope,
            dispatcherProvider = dispatcherProvider,
            settings = settings,
            gatewaySwitch = gatewaySwitch,
        )
    }

    @Test
    fun `add - single local path is stored and returned by flow`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val path = LocalPath.build("/storage/emulated/0/Download")
        repo.add(path)

        repo.favoritePaths.first { it.isNotEmpty() } shouldContainExactly listOf(path)
        repo.isFavorite(path) shouldBe true
    }

    @Test
    fun `add - dedupe via matches() avoids duplicates`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val path = LocalPath.build("/sdcard/dup")
        repo.add(path)
        repo.add(path)  // second add is silently deduped

        repo.favoritePaths.first { it.isNotEmpty() } shouldHaveSize 1
    }

    @Test
    fun `add - Local and SAF paths with same string stay distinct`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val localPath = LocalPath.build("/Documents")
        val safPath = SAFPath(
            treeRoot = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            segments = listOf("Documents"),
        )

        repo.add(localPath)
        repo.add(safPath)

        val stored = repo.favoritePaths.first { it.size == 2 }
        stored shouldHaveSize 2
    }

    @Test
    fun `addAll - dedupes within the input batch as well as against current`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        // Pass duplicates within the batch — should still result in one entry per unique path.
        repo.addAll(listOf(a, a, b, b, a))

        val stored = repo.favoritePaths.first { it.size == 2 }
        stored shouldContainExactly listOf(a, b)
    }

    @Test
    fun `addAll - dedupes incoming paths against existing storage`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.add(a)
        // Re-adding A alongside new B should only persist B as the new entry.
        repo.addAll(listOf(a, b))

        val stored = repo.favoritePaths.first { it.size == 2 }
        stored shouldContainExactly listOf(a, b)
    }

    @Test
    fun `addAll - returns only the paths that were actually added`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.add(a)

        repo.addAll(listOf(a, b, b)) shouldContainExactly listOf(b)
        repo.addAll(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `remove - drops the path, isFavorite reflects it`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val path = LocalPath.build("/will-be-removed")
        repo.add(path)
        repo.favoritePaths.first { it.isNotEmpty() }
        repo.isFavorite(path) shouldBe true

        repo.remove(path)
        repo.favoritePaths.first { it.isEmpty() } shouldBe emptyList()
        repo.isFavorite(path) shouldBe false
    }

    @Test
    fun `removeAll - drops every matching path in the input list`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        repo.addAll(listOf(a, b, c))

        repo.removeAll(listOf(a, c))

        repo.favoritePaths.first { it.size == 1 } shouldContainExactly listOf(b)
    }

    @Test
    fun `toggle - empty list adds, returns Added`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val path = LocalPath.build("/toggle-target")
        repo.toggle(path) shouldBe ExplorerFavoritesRepo.ToggleResult.Added(path)
        repo.favoritePaths.first { it.isNotEmpty() }
        repo.isFavorite(path) shouldBe true
    }

    @Test
    fun `toggle - present path removes, returns Removed with its original index`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val other = LocalPath.build("/p/other")
        val path = LocalPath.build("/toggle-target")
        repo.addAll(listOf(other, path))

        repo.toggle(path) shouldBe ExplorerFavoritesRepo.ToggleResult.Removed(
            ExplorerFavoritesRepo.RemovedFavorite(path, 1)
        )
        repo.favoritePaths.first { it.size == 1 }
        repo.isFavorite(path) shouldBe false
    }

    @Test
    fun `removeAllForUndo - captures each removed entry with its original index`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        val d = LocalPath.build("/p/d")
        repo.addAll(listOf(a, b, c, d))

        val removed = repo.removeAllForUndo(listOf(b, d, LocalPath.build("/p/missing")))

        removed shouldContainExactly listOf(
            ExplorerFavoritesRepo.RemovedFavorite(b, 1),
            ExplorerFavoritesRepo.RemovedFavorite(d, 3),
        )
        repo.favoritePaths.first { it.size == 2 } shouldContainExactly listOf(a, c)
    }

    @Test
    fun `removeAllForUndo - no match is a no-op`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        repo.add(a)

        repo.removeAllForUndo(listOf(LocalPath.build("/p/missing"))) shouldBe emptyList()
        repo.favoritePaths.first { it.isNotEmpty() } shouldContainExactly listOf(a)
    }

    @Test
    fun `removeAllForUndo + addAllAt - round-trip preserves every position`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        val d = LocalPath.build("/p/d")
        repo.addAll(listOf(a, b, c, d))

        val removed = repo.removeAllForUndo(listOf(a, c))
        repo.favoritePaths.first { it.size == 2 } shouldContainExactly listOf(b, d)

        repo.addAllAt(removed)
        repo.favoritePaths.first { it.size == 4 } shouldContainExactly listOf(a, b, c, d)
    }

    @Test
    fun `addAllAt - skips entries that are already present`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.addAll(listOf(a, b))

        repo.addAllAt(
            listOf(
                ExplorerFavoritesRepo.RemovedFavorite(a, 0),
                ExplorerFavoritesRepo.RemovedFavorite(LocalPath.build("/p/c"), 99),
            )
        )

        repo.favoritePaths.first { it.size == 3 } shouldContainExactly
            listOf(a, b, LocalPath.build("/p/c"))
    }

    @Test
    fun `removeForUndo - existing path returns RemovedFavorite with original index`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        repo.addAll(listOf(a, b, c))

        val removed = repo.removeForUndo(b)
        removed shouldBe ExplorerFavoritesRepo.RemovedFavorite(b, 1)
        repo.favoritePaths.first { it.size == 2 } shouldContainExactly listOf(a, c)
    }

    @Test
    fun `removeForUndo - missing path returns null and is no-op`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        repo.add(a)

        val removed = repo.removeForUndo(LocalPath.build("/p/missing"))
        removed shouldBe null
        repo.favoritePaths.first { it.isNotEmpty() } shouldContainExactly listOf(a)
    }

    @Test
    fun `removeForUndo + addAt - round-trip preserves position`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        val d = LocalPath.build("/p/d")
        repo.addAll(listOf(a, b, c, d))

        val removed = repo.removeForUndo(c)!!
        repo.addAt(removed.path, removed.originalIndex)

        repo.favoritePaths.first { it.size == 4 } shouldContainExactly listOf(a, b, c, d)
    }

    @Test
    fun `addAt - inserts at given index`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")
        repo.addAll(listOf(a, c))
        repo.addAt(b, 1)

        repo.favoritePaths.first { it.size == 3 } shouldContainExactly listOf(a, b, c)
    }

    @Test
    fun `addAt - dedupes if path already present`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        repo.add(a)
        repo.addAt(a, 0)

        repo.favoritePaths.first { it.isNotEmpty() } shouldHaveSize 1
    }

    @Test
    fun `addAt - clamps out-of-range index`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val repo = freshRepo(scope)

        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.add(a)
        // Index 99 is way past the end — should clamp to size (1) and append.
        repo.addAt(b, 99)

        repo.favoritePaths.first { it.size == 2 } shouldContainExactly listOf(a, b)
    }

    @Test
    fun `favorites flow - emits Unavailable when gateway lookup throws ReadException`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val gatewaySwitch = mockk<GatewaySwitch>(relaxed = true)
        val path = LocalPath.build("/missing")
        coEvery { gatewaySwitch.lookup(any(), any()) } throws ReadException(path = path)

        val repo = freshRepo(scope, gatewaySwitch)
        repo.add(path)

        val resolved = repo.favorites.first { items -> items.any { it.isUnavailable } }
        resolved shouldHaveSize 1
        resolved.first().isUnavailable shouldBe true
    }

    @Test
    fun `refresh - re-runs the resolver pipeline`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
        val gatewaySwitch = mockk<GatewaySwitch>(relaxed = true)
        val path = LocalPath.build("/will-refresh")
        coEvery { gatewaySwitch.lookup(any(), any()) } throws ReadException(path = path)

        val repo = freshRepo(scope, gatewaySwitch)
        repo.add(path)
        // Wait for the first resolution pass so the lookup count reflects it.
        repo.favorites.first { items -> items.any { it.isUnavailable } }

        repo.refresh()
        // After refresh, the resolver pipeline must call lookup a second time.
        coVerify(atLeast = 2) { gatewaySwitch.lookup(any(), any()) }
    }
}
