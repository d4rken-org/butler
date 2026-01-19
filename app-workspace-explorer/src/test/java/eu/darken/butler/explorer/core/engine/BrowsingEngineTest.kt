package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.progress.Progress
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class BrowsingEngineTest : BaseTest() {

    @Nested
    inner class StateTests {

        @Test
        fun `State - initial state has null values`() {
            val state = BrowsingEngine.State()

            state.location shouldBe null
            state.error shouldBe null
            state.breadcrumbs shouldBe null
        }

        @Test
        fun `State - copy preserves location when setting error`() {
            val location = ExplorerLocation.Home(
                items = emptyList(),
                progress = null,
            )
            val state = BrowsingEngine.State(location = location)

            val copied = state.copy(error = RuntimeException("test"))

            copied.location shouldBe location
            copied.error?.message shouldBe "test"
        }

        @Test
        fun `State - error can be set with null location`() {
            val state = BrowsingEngine.State()

            val errorState = state.copy(error = RuntimeException("fail"), location = null)

            errorState.location shouldBe null
            errorState.error?.message shouldBe "fail"
        }
    }

    @Nested
    inner class ExplorerLocationTests {

        @Test
        fun `Home location - isLoading is true when progress is non-null`() {
            val loading = ExplorerLocation.Home(progress = Progress.Data())
            val notLoading = ExplorerLocation.Home(progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Device location - isLoading is true when progress is non-null`() {
            val loading = ExplorerLocation.Device(progress = Progress.Data())
            val notLoading = ExplorerLocation.Device(progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Directory location - isLoading is true when progress is non-null`() {
            val path = LocalPath.build("/sdcard")
            val loading = ExplorerLocation.Directory(path = path, progress = Progress.Data())
            val notLoading = ExplorerLocation.Directory(path = path, progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Home location - locationId is constant`() {
            val home1 = ExplorerLocation.Home()
            val home2 = ExplorerLocation.Home(items = emptyList())

            home1.locationId shouldBe "location://home"
            home2.locationId shouldBe "location://home"
            home1.locationId shouldBe home2.locationId
        }

        @Test
        fun `Device location - locationId is constant`() {
            val device = ExplorerLocation.Device()

            device.locationId shouldBe "location://device"
        }

        @Test
        fun `Directory location - locationId includes path`() {
            val path1 = LocalPath.build("/sdcard/Documents")
            val path2 = LocalPath.build("/sdcard/Pictures")

            val dir1 = ExplorerLocation.Directory(path = path1)
            val dir2 = ExplorerLocation.Directory(path = path2)

            dir1.locationId shouldBe "location://directory//sdcard/Documents"
            dir2.locationId shouldBe "location://directory//sdcard/Pictures"
        }
    }
}
