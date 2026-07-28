package eu.darken.butler.explorer.ui.explorer.elements

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FavoritesSectionIndexTest : BaseTest() {

    private fun path(name: String) = LocalPath.build("/storage/emulated/0/$name")

    private fun directory(name: String) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = path(name),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
    )

    private fun favorite(name: String) = FavoriteItem(
        path = path(name),
        state = FavoriteItem.State.Resolving,
    )

    private fun state(
        items: List<ExplorerItem>?,
        favorites: List<FavoriteItem>,
        showSection: Boolean = favorites.isNotEmpty(),
        error: Throwable? = null,
    ) = ExplorerWorkspaceViewModel.State(
        items = items,
        favorites = favorites,
        showHomeFavoritesSection = showSection,
        error = error,
    )

    @Test
    fun `index sits behind the content items, divider and section header`() {
        val target = state(
            items = listOf(directory("A"), directory("B")),
            favorites = listOf(favorite("Fav1"), favorite("Fav2")),
        )

        target.favoriteContentIndex(path("Fav1")) shouldBe 4
        target.favoriteContentIndex(path("Fav2")) shouldBe 5
    }

    @Test
    fun `empty content still occupies one lazy slot`() {
        val target = state(
            items = emptyList(),
            favorites = listOf(favorite("Fav1")),
        )

        target.favoriteContentIndex(path("Fav1")) shouldBe 3
    }

    @Test
    fun `no index while content is still loading`() {
        val target = state(
            items = null,
            favorites = listOf(favorite("Fav1")),
        )

        target.favoriteContentIndex(path("Fav1")) shouldBe null
    }

    @Test
    fun `an errored location renders no leading items at all`() {
        val target = state(
            items = null,
            favorites = listOf(favorite("Fav1"), favorite("Fav2")),
            error = IllegalStateException("boom"),
        )

        target.favoriteContentIndex(path("Fav2")) shouldBe 3
    }

    @Test
    fun `no index while the favorites section is hidden`() {
        val target = state(
            items = listOf(directory("A")),
            favorites = listOf(favorite("Fav1")),
            showSection = false,
        )

        target.favoriteContentIndex(path("Fav1")) shouldBe null
    }

    @Test
    fun `no index for a path that is not favorited`() {
        val target = state(
            items = listOf(directory("A")),
            favorites = listOf(favorite("Fav1")),
        )

        target.favoriteContentIndex(path("Nope")) shouldBe null
    }
}
