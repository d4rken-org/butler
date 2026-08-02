package eu.darken.butler.workspace.core.session.db

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    /**
     * The complete current format, written out by hand.
     *
     * Do NOT replace this with JSON produced by encoding a model: a fixture generated from the code
     * under test moves along with every rename and would happily agree with a format break that
     * orphans every existing user's saved state. Every character here is the wire contract - field
     * names, nesting depth, the version marker, the "TOP"/"BOTTOM" literals, "index"/"offset", and
     * Workspace.Id used as a bare UUID string map key.
     */
    private val goldenBlob = """
        {
          "version": 1,
          "focusedWorkspaceId": "${idA.id}",
          "paneSelections": {
            "0": "${idA.id}",
            "1": "${idB.id}"
          },
          "scrollPositions": {
            "${idA.id}": {
              "list#location://home": {"index": 12, "offset": 34},
              "grid#location://home": {"index": 3, "offset": 0}
            },
            "${idB.id}": {
              "apps#list": {"index": 7, "offset": 8}
            }
          },
          "barCollapse": {
            "${idA.id}": {
              "TOP": {"toolbar": 1.0, "infobar": 0.0},
              "BOTTOM": {"actions": 0.0, "clipboard": 1.0}
            },
            "${idB.id}": {
              "TOP": {"toolbar": 1.0}
            }
          },
          "viewPrefs": {
            "${idA.id}": {
              "explorer.sort": {"default": {"mode": "NAME"}, "rules": {}}
            }
          }
        }
    """.trimIndent()

    /** The model [goldenBlob] describes, also written out by hand. */
    private val goldenState = WorkspaceUIState(
        version = 1,
        focusedWorkspaceId = idA,
        paneSelections = mapOf(0 to idA, 1 to idB),
        scrollPositions = mapOf(
            idA to mapOf(
                "list#location://home" to WorkspaceScrollPosition(12, 34),
                "grid#location://home" to WorkspaceScrollPosition(3, 0),
            ),
            idB to mapOf("apps#list" to WorkspaceScrollPosition(7, 8)),
        ),
        barCollapse = mapOf(
            idA to mapOf(
                "TOP" to mapOf("toolbar" to 1f, "infobar" to 0f),
                "BOTTOM" to mapOf("actions" to 0f, "clipboard" to 1f),
            ),
            idB to mapOf("TOP" to mapOf("toolbar" to 1f)),
        ),
        // The envelope is the contract here; what is inside the payload belongs to the writing module
        viewPrefs = mapOf(
            idA to mapOf(
                "explorer.sort" to buildJsonObject {
                    put("default", buildJsonObject { put("mode", JsonPrimitive("NAME")) })
                    put("rules", JsonObject(emptyMap()))
                },
            ),
        ),
    )

    @Test
    fun `the current format decodes field by field`() {
        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), goldenBlob)

        decoded.version shouldBe 1
        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA, 1 to idB)

        decoded.scrollPositions.keys shouldBe setOf(idA, idB)
        decoded.scrollPositions[idA] shouldBe mapOf(
            "list#location://home" to WorkspaceScrollPosition(12, 34),
            "grid#location://home" to WorkspaceScrollPosition(3, 0),
        )
        decoded.scrollPositions[idB] shouldBe mapOf("apps#list" to WorkspaceScrollPosition(7, 8))

        decoded.barCollapse.keys shouldBe setOf(idA, idB)
        decoded.barCollapse[idA]?.keys shouldBe setOf("TOP", "BOTTOM")
        decoded.barCollapse[idA]?.get("TOP") shouldBe mapOf("toolbar" to 1f, "infobar" to 0f)
        decoded.barCollapse[idA]?.get("BOTTOM") shouldBe mapOf("actions" to 0f, "clipboard" to 1f)
        decoded.barCollapse[idB] shouldBe mapOf("TOP" to mapOf("toolbar" to 1f))

        decoded.viewPrefs.keys shouldBe setOf(idA)
        decoded.viewPrefs[idA]?.keys shouldBe setOf("explorer.sort")

        decoded shouldBe goldenState
    }

    @Test
    fun `the current format is written back the same way`() {
        val encoded = json.encodeToString(WorkspaceUIState.serializer(), goldenState)

        json.parseToJsonElement(encoded).jsonObject shouldBe json.parseToJsonElement(goldenBlob).jsonObject
    }

    @Test
    fun `legacy rows without scroll positions decode`() {
        val legacy = """
            {"focusedWorkspaceId":"${idA.id}","paneSelections":{"0":"${idA.id}","1":"${idB.id}"}}
        """.trimIndent()

        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), legacy)

        decoded.focusedWorkspaceId shouldBe idA
        decoded.paneSelections shouldBe mapOf(0 to idA, 1 to idB)
        decoded.scrollPositions shouldBe emptyMap()
        decoded.barCollapse shouldBe emptyMap()
        decoded.viewPrefs shouldBe emptyMap()
        // Rows predate the marker; they are the format the marker calls v1
        decoded.version shouldBe WorkspaceUIState.CURRENT_VERSION
    }

    /** A row written by the build that had bar collapse state but not yet view prefs. */
    @Test
    fun `rows without view prefs decode`() {
        val previous = """
            {"focusedWorkspaceId":"${idA.id}","paneSelections":{},"barCollapse":{"${idA.id}":{"TOP":{"toolbar":1.0}}}}
        """.trimIndent()

        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), previous)

        decoded.barCollapse shouldBe mapOf(idA to mapOf("TOP" to mapOf("toolbar" to 1f)))
        decoded.viewPrefs shouldBe emptyMap()
    }

    /**
     * Payloads are opaque, so an entry a newer build wrote has to round-trip through this one
     * untouched rather than be dropped or rewritten.
     */
    @Test
    fun `an unknown view pref payload round-trips unchanged`() {
        val stored = """
            {"viewPrefs":{"${idA.id}":{"future.slot":{"deeply":{"nested":[1,2,3]}}}}}
        """.trimIndent()

        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), stored)
        val reEncoded = json.encodeToString(WorkspaceUIState.serializer(), decoded)

        json.parseToJsonElement(reEncoded).jsonObject.getValue("viewPrefs") shouldBe
            json.parseToJsonElement(stored).jsonObject.getValue("viewPrefs")
    }

    /** A row written by the build that had scroll positions but not yet bar collapse state. */
    @Test
    fun `rows without bar collapse state decode`() {
        val previous = """
            {"focusedWorkspaceId":"${idA.id}","paneSelections":{},"scrollPositions":{"${idA.id}":{"history":{"index":4,"offset":9}}}}
        """.trimIndent()

        val decoded = json.decodeFromString(WorkspaceUIState.serializer(), previous)

        decoded.scrollPositions shouldBe mapOf(idA to mapOf("history" to WorkspaceScrollPosition(4, 9)))
        decoded.barCollapse shouldBe emptyMap()
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
            barCollapse = mapOf(
                idA to mapOf(
                    "TOP" to mapOf("toolbar" to 1f, "infobar" to 0f),
                    "BOTTOM" to mapOf("actions" to 0f),
                ),
                idB to mapOf("TOP" to mapOf("toolbar" to 0f)),
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
            barCollapse = mapOf(idB to mapOf("TOP" to mapOf("toolbar" to 1f))),
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
