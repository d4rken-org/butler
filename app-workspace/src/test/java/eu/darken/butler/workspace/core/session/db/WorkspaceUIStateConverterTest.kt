package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

/**
 * The converter is the only thing between a stored blob and the running app, and it deliberately
 * swallows decode failures so a broken blob can never keep the app from starting. That safety net
 * also hides a format break, so the shape it accepts is pinned here.
 */
class WorkspaceUIStateConverterTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val converter = WorkspaceUIStateConverter(json)

    private val idA = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000a"))
    private val idB = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000b"))

    private val state = WorkspaceUIState(
        focusedWorkspaceId = idA,
        paneSelections = mapOf(0 to idA, 1 to idB),
        scrollPositions = mapOf(idA to mapOf("list#location://home" to WorkspaceScrollPosition(12, 34))),
        barCollapse = mapOf(idA to mapOf("TOP" to mapOf("toolbar" to 1f), "BOTTOM" to mapOf("actions" to 0f))),
    )

    @Test
    fun `a stored blob decodes into the expected state`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}", "1": "${idB.id}"},
              "scrollPositions": {"${idA.id}": {"list#location://home": {"index": 12, "offset": 34}}},
              "barCollapse": {"${idA.id}": {"TOP": {"toolbar": 1.0}, "BOTTOM": {"actions": 0.0}}}
            }
        """.trimIndent()

        converter.toUIState(stored) shouldBe state
    }

    @Test
    fun `a garbage blob falls back to the default instead of throwing`() {
        converter.toUIState("¯\\_(ツ)_/¯") shouldBe WorkspaceUIState()
        converter.toUIState("") shouldBe WorkspaceUIState()
        converter.toUIState("""{"focusedWorkspaceId": 42}""") shouldBe WorkspaceUIState()
        converter.toUIState("""{"scrollPositions": {"${idA.id}": {"history": "nope"}}}""") shouldBe WorkspaceUIState()
    }

    @Test
    fun `a blob written by a newer build still decodes`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}"},
              "someFutureField": {"nested": [1, 2, 3]},
              "anotherOne": "whatever"
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA)
    }

    @Test
    fun `state survives a roundtrip through the converter`() {
        converter.toUIState(converter.fromUIState(state)) shouldBe state
    }

    @Test
    fun `a null state is stored as the default`() {
        json.parseToJsonElement(converter.fromUIState(null)).jsonObject shouldBe
            json.parseToJsonElement(converter.fromUIState(WorkspaceUIState())).jsonObject

        converter.toUIState(converter.fromUIState(null)) shouldBe WorkspaceUIState()
    }
}
