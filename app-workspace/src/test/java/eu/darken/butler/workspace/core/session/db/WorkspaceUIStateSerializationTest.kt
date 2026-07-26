package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

/**
 * The UI state is stored as a JSON blob in a plain TEXT column, so adding a field is invisible to
 * Room - but it must stay readable in both directions: old rows decode into a new build, and rows
 * written by a new build still decode in an older one.
 */
class WorkspaceUIStateSerializationTest : BaseTest() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val idA = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000a"))
    private val idB = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000b"))

    @Test
    fun `legacy rows without scroll positions decode`() {
        val legacy = """
            {"focusedWorkspaceId":"${idA.id}","paneSelections":{"0":"${idA.id}","1":"${idB.id}"}}
        """.trimIndent()

        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), legacy)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA, 1 to idB)
        decoded.scrollPositions shouldBe emptyMap()
    }

    @Test
    fun `scroll positions survive a roundtrip`() {
        val state = WorkspaceUIState(
            focusedWorkspaceId = idA,
            paneSelections = mapOf(0 to idA),
            scrollPositions = mapOf(
                idA to mapOf(
                    "list#location://home" to WorkspaceScrollPosition(12, 34),
                    "grid#location://home" to WorkspaceScrollPosition(3),
                ),
                idB to mapOf("apps#list" to WorkspaceScrollPosition(7, 8)),
            ),
        )

        val encoded = json.encodeToString(WorkspaceUIState.serializer(), state)

        json.decodeFromString(WorkspaceUIState.serializer(), encoded) shouldBe state
    }

    @Test
    fun `a new blob still decodes into the previous format`() {
        val state = WorkspaceUIState(
            focusedWorkspaceId = idB,
            paneSelections = mapOf(0 to idB),
            scrollPositions = mapOf(idB to mapOf("history" to WorkspaceScrollPosition(5))),
        )

        val encoded = json.encodeToString(WorkspaceUIState.serializer(), state)
        val legacy = json.decodeFromString(LegacyWorkspaceUIState.serializer(), encoded)

        legacy.focusedWorkspaceId shouldBe idB
        legacy.paneSelections shouldBe mapOf(0 to idB)
    }
}

/** Stand-in for the pre-scroll shape of [WorkspaceUIState], as an older build would see it. */
@Serializable
private data class LegacyWorkspaceUIState(
    @Serializable(with = WorkspaceIdSerializer::class)
    val focusedWorkspaceId: Workspace.Id? = null,
    val paneSelections: Map<Int, @Serializable(with = WorkspaceIdSerializer::class) Workspace.Id> = emptyMap(),
)
