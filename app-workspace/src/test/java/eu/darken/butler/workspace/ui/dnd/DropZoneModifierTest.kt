package eu.darken.butler.workspace.ui.dnd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class DropZoneModifierTest : ComposeTest() {

    private val download = LocalPath.build("/storage/emulated/0/Download")

    @Test
    fun `a zone registers its bounds`() {
        val registry = DropZoneRegistry()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDropZoneRegistry provides registry) {
                Box(modifier = Modifier.fillMaxSize().dropZone(key = "row", destination = download))
            }
        }
        composeTestRule.waitForIdle()

        registry.zoneAt(Offset(1f, 1f))?.destination shouldBe download
    }

    @Test
    fun `a zone unregisters when its destination goes away`() {
        val registry = DropZoneRegistry()
        var destination by mutableStateOf<APath<*>?>(download)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDropZoneRegistry provides registry) {
                Box(modifier = Modifier.fillMaxSize().dropZone(key = "row", destination = destination))
            }
        }
        composeTestRule.waitForIdle()
        registry.zoneAt(Offset(1f, 1f))?.destination shouldBe download

        destination = null
        composeTestRule.waitForIdle()

        registry.zoneAt(Offset(1f, 1f)) shouldBe null
    }

    @Test
    fun `a zone unregisters when it leaves the composition`() {
        val registry = DropZoneRegistry()
        var present by mutableStateOf(true)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDropZoneRegistry provides registry) {
                if (present) {
                    Box(modifier = Modifier.fillMaxSize().dropZone(key = "row", destination = download))
                }
            }
        }
        composeTestRule.waitForIdle()
        registry.zoneAt(Offset(1f, 1f))?.destination shouldBe download

        present = false
        composeTestRule.waitForIdle()

        registry.zoneAt(Offset(1f, 1f)) shouldBe null
    }
}
