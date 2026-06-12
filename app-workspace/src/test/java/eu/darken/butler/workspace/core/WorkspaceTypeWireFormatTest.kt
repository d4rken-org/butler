package eu.darken.butler.workspace.core

import eu.darken.butler.apps.core.arguments.DetailTab
import eu.darken.butler.workspace.core.serialization.WorkspaceTypeConverter
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Pins the wire format of enums that are persisted in users' saved sessions (Room session DB
 * via [WorkspaceTypeConverter] and kotlinx JSON inside serialized arguments).
 *
 * If one of these tests fails, you are changing how a constant is persisted while existing
 * sessions still contain the old value. A mismatch makes the session restore fail (saved rows
 * are preserved by the auto-save guard, but they won't load). Either keep the wire value stable
 * or add explicit migration handling for the legacy value before updating this test.
 */
class WorkspaceTypeWireFormatTest : BaseTest() {

    @Test
    fun `Workspace Type has stable Room wire format`() {
        val converter = WorkspaceTypeConverter()
        Workspace.Type.entries.map { converter.fromType(it) } shouldBe listOf(
            "TEMPLATES",
            "EXPLORER",
            "SEARCHER",
            "EDITOR",
            "APPS",
            "APP_DETAILS",
            "SAVER",
            "DEVELOPER",
            "HISTORY",
        )
    }

    @Test
    fun `DetailTab has stable JSON wire format`() {
        val json = Json
        DetailTab.entries.map { json.encodeToJsonElement(it).jsonPrimitive.content } shouldBe listOf(
            "OVERVIEW",
            "PACKAGE_INFO",
            "COMPONENTS",
        )
    }
}
