package eu.darken.butler.explorer.core.sizes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TopLevelProgressTest : BaseTest() {

    private fun progress(vararg children: String) = TopLevelProgress(
        rootKey = "/a",
        children = children.toSet(),
    )

    @Test
    fun `the child in progress is not counted yet`() {
        val progress = progress("/a/one", "/a/two")

        progress.total shouldBe 2
        progress.done shouldBe 0
        progress.current shouldBe null

        progress.onSeen("/a/one/file")
        progress.done shouldBe 0
        progress.current shouldBe "/a/one"

        progress.onSeen("/a/two/deep/file")
        progress.done shouldBe 1
        progress.current shouldBe "/a/two"
    }

    @Test
    fun `revisiting an earlier child does not move the count back`() {
        val progress = progress("/a/one", "/a/two")

        progress.onSeen("/a/one")
        progress.onSeen("/a/two")
        progress.onSeen("/a/one/file")

        progress.done shouldBe 1
        progress.current shouldBe "/a/one"
    }

    @Test
    fun `paths that are not a known child are ignored`() {
        val progress = progress("/a/one")

        progress.onSeen("/a/unlisted/file")
        progress.onSeen("/b/one/file")
        progress.onSeen("/a")
        progress.onSeen("/ab/one")

        progress.done shouldBe 0
        progress.current shouldBe null
    }

    @Test
    fun `finishing counts every child that was seen`() {
        val progress = progress("/a/one", "/a/two", "/a/three")

        progress.onSeen("/a/one/file")
        progress.onSeen("/a/two/file")
        progress.finish()

        progress.done shouldBe 2

        val complete = progress("/a/one", "/a/two")
        complete.onSeen("/a/one")
        complete.onSeen("/a/two")
        complete.finish()
        complete.done shouldBe 2
    }

    @Test
    fun `the filesystem root resolves its own children`() {
        val progress = TopLevelProgress(rootKey = "/", children = setOf("/one", "/two"))

        progress.onSeen("/one/file")
        progress.onSeen("/two")

        progress.done shouldBe 1
        progress.current shouldBe "/two"
    }
}
