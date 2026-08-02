package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

/**
 * The converter is the only thing between a stored blob and the running app, and it deliberately
 * swallows decode failures so a broken blob can never keep the app from starting. That safety net
 * also hides a format break, so the shape it accepts is pinned here - together with how far a break
 * is allowed to spread: one unreadable field must not take the readable ones with it.
 */
class WorkspaceUIStateConverterTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val converter = WorkspaceUIStateConverter(json)

    private val idA = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000a"))
    private val idB = Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000b"))

    private val logs = mutableListOf<Pair<Logging.Priority, String>>()
    private val logCapture = object : Logging.Logger {
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            logs.add(priority to message)
        }
    }

    @BeforeEach
    fun installLogCapture() = Logging.install(logCapture)

    @AfterEach
    fun removeLogCapture() = Logging.remove(logCapture)

    private fun warnings() = logs.filter { it.first == Logging.Priority.WARN }.map { it.second }

    private val state = WorkspaceUIState(
        focusedWorkspaceId = idA,
        paneSelections = mapOf(0 to idA, 1 to idB),
        scrollPositions = mapOf(idA to mapOf("list#location://home" to WorkspaceScrollPosition(12, 34))),
        barCollapse = mapOf(idA to mapOf("TOP" to mapOf("toolbar" to 1f), "BOTTOM" to mapOf("actions" to 0f))),
        viewPrefs = mapOf(idA to mapOf("explorer.sort" to buildJsonObject { put("default", JsonPrimitive("NAME")) })),
    )

    @Test
    fun `a stored blob decodes into the expected state`() {
        val stored = """
            {
              "version": 1,
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}", "1": "${idB.id}"},
              "scrollPositions": {"${idA.id}": {"list#location://home": {"index": 12, "offset": 34}}},
              "barCollapse": {"${idA.id}": {"TOP": {"toolbar": 1.0}, "BOTTOM": {"actions": 0.0}}},
              "viewPrefs": {"${idA.id}": {"explorer.sort": {"default": "NAME"}}}
            }
        """.trimIndent()

        converter.toUIState(stored) shouldBe state
        warnings() shouldBe emptyList()
    }

    /**
     * [toUIState] rebuilds the state from explicitly named fields, so the generated serializer that
     * the golden/serialization tests exercise would not notice a missing or misspelled decode here.
     */
    @Test
    fun `a non-empty view prefs map survives the converter's field-by-field decode`() {
        val decoded = converter.toUIState(converter.fromUIState(state))

        decoded.viewPrefs shouldBe state.viewPrefs
        warnings() shouldBe emptyList()
    }

    @Test
    fun `a legacy blob without view prefs decodes to an empty map`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}"},
              "scrollPositions": {"${idA.id}": {"history": {"index": 4, "offset": 9}}}
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.viewPrefs shouldBe emptyMap()
        decoded.focusedWorkspaceId shouldBe idA
        warnings() shouldBe emptyList()
    }

    @Test
    fun `a corrupt view prefs map does not take the other fields with it`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}"},
              "scrollPositions": {"${idA.id}": {"history": {"index": 4, "offset": 9}}},
              "barCollapse": {"${idA.id}": {"TOP": {"toolbar": 1.0}}},
              "viewPrefs": [1, 2]
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA)
        decoded.scrollPositions shouldBe mapOf(idA to mapOf("history" to WorkspaceScrollPosition(4, 9)))
        decoded.barCollapse shouldBe mapOf(idA to mapOf("TOP" to mapOf("toolbar" to 1f)))
        decoded.viewPrefs shouldBe emptyMap()

        warnings().size shouldBe 1
        warnings().single().contains("viewPrefs") shouldBe true
    }

    @Test
    fun `a default state is written with an empty view prefs field`() {
        val encoded = json.parseToJsonElement(converter.fromUIState(WorkspaceUIState())).jsonObject

        encoded.getValue("viewPrefs") shouldBe JsonObject(emptyMap())
    }

    @Test
    fun `a blob that is not JSON at all falls back to the default instead of throwing`() {
        converter.toUIState("¯\\_(ツ)_/¯") shouldBe WorkspaceUIState()
        converter.toUIState("") shouldBe WorkspaceUIState()
        converter.toUIState("[1,2,3]") shouldBe WorkspaceUIState()
        converter.toUIState("null") shouldBe WorkspaceUIState()

        warnings().size shouldBe 4
        warnings().all { it.contains("ALL of it is DISCARDED") } shouldBe true
    }

    /**
     * The nested scroll map is the biggest and most breakable part of the blob, while the focus and
     * pane ids are one string each and are what the user actually notices going missing.
     */
    @Test
    fun `a corrupt scroll map does not take focus and panes with it`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}", "1": "${idB.id}"},
              "scrollPositions": {"${idA.id}": {"history": "nope"}},
              "barCollapse": {"${idA.id}": {"TOP": {"toolbar": 1.0}}}
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA, 1 to idB)
        decoded.barCollapse shouldBe mapOf(idA to mapOf("TOP" to mapOf("toolbar" to 1f)))
        decoded.scrollPositions shouldBe emptyMap()

        warnings().size shouldBe 1
        warnings().single().contains("scrollPositions") shouldBe true
    }

    @Test
    fun `a corrupt bar collapse map does not take focus and panes with it`() {
        val stored = """
            {
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}"},
              "scrollPositions": {"${idA.id}": {"history": {"index": 4, "offset": 9}}},
              "barCollapse": {"${idA.id}": {"TOP": 1.0}}
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA)
        decoded.scrollPositions shouldBe mapOf(idA to mapOf("history" to WorkspaceScrollPosition(4, 9)))
        decoded.barCollapse shouldBe emptyMap()

        warnings().size shouldBe 1
        warnings().single().contains("barCollapse") shouldBe true
    }

    @Test
    fun `an unreadable focus id does not take the rest with it`() {
        val stored = """
            {
              "focusedWorkspaceId": 42,
              "paneSelections": {"0": "${idA.id}"},
              "scrollPositions": {"${idA.id}": {"history": {"index": 4, "offset": 9}}}
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe null
        decoded.paneSelections shouldBe mapOf(0 to idA)
        decoded.scrollPositions shouldBe mapOf(idA to mapOf("history" to WorkspaceScrollPosition(4, 9)))

        warnings().single().contains("focusedWorkspaceId") shouldBe true
    }

    @Test
    fun `every field can be lost at once without throwing`() {
        val stored = """
            {
              "focusedWorkspaceId": 42,
              "paneSelections": "nope",
              "scrollPositions": [1, 2],
              "barCollapse": 7
            }
        """.trimIndent()

        converter.toUIState(stored) shouldBe WorkspaceUIState()
        warnings().size shouldBe 4
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

    /**
     * "User ran a newer build and went back" produces the same visible symptom as a corrupt row -
     * state that quietly went missing - so the version marker has to make the two tellable apart in
     * the log.
     */
    @Test
    fun `a blob from a newer format version is reported as such`() {
        val stored = """
            {
              "version": 999,
              "focusedWorkspaceId": "${idA.id}",
              "paneSelections": {"0": "${idA.id}"},
              "somethingFromTheFuture": true
            }
        """.trimIndent()

        val decoded = converter.toUIState(stored)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA)
        // What this build decoded is v1, no matter what the row claimed
        decoded.version shouldBe WorkspaceUIState.CURRENT_VERSION

        warnings().single().contains("NEWER build") shouldBe true
        warnings().single().contains("999") shouldBe true
    }

    @Test
    fun `the current version is not reported`() {
        converter.toUIState("""{"version": ${WorkspaceUIState.CURRENT_VERSION}}""") shouldBe WorkspaceUIState()
        converter.toUIState("{}") shouldBe WorkspaceUIState()

        warnings() shouldBe emptyList()
    }

    @Test
    fun `an unreadable version is treated as unversioned`() {
        val stored = """
            {"version": "beta", "focusedWorkspaceId": "${idA.id}"}
        """.trimIndent()

        converter.toUIState(stored).focusedWorkspaceId shouldBe idA

        warnings().single().contains("version") shouldBe true
    }

    @Test
    fun `the version marker is written on every save`() {
        val encoded = json.parseToJsonElement(converter.fromUIState(state)).jsonObject
        encoded.getValue("version").jsonPrimitive.int shouldBe WorkspaceUIState.CURRENT_VERSION

        val fromNull = json.parseToJsonElement(converter.fromUIState(null)).jsonObject
        fromNull.getValue("version").jsonPrimitive.int shouldBe WorkspaceUIState.CURRENT_VERSION
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
