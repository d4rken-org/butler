package eu.darken.butler.workspace.core

import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.contracts.developer.DeveloperArguments
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

/**
 * Guards session-restore compatibility of persisted workspace arguments.
 *
 * Policy: preference/UI-style fields must have defaults (or be nullable) so that sessions saved
 * by older app versions keep restoring after fields are added. Content-identity fields
 * (e.g. AppDetails packageName, Saver sourceUris) stay required on purpose — a row missing its
 * identity fails deserialization and is skipped during restore instead of producing a broken tab.
 *
 * If a "decodes from minimal JSON" test fails, you added a required field to persisted
 * arguments — give it a default unless it is the content identity of the workspace.
 */
class WorkspaceArgumentsRestoreSafetyTest : BaseTest() {

    private val json = SerializationIOModule().json()

    @Test
    fun `TemplatesArguments decodes from minimal JSON`() {
        json.decodeFromString<TemplatesArguments>("""{"type":"arguments"}""") shouldBe TemplatesArguments.Default()
    }

    @Test
    fun `ExplorerArguments decodes from minimal JSON`() {
        json.decodeFromString<ExplorerArguments>("""{"type":"standard"}""") shouldBe ExplorerArguments.Default()
    }

    @Test
    fun `SearcherArguments decodes from minimal JSON`() {
        json.decodeFromString<SearcherArguments>("""{"type":"arguments"}""") shouldBe SearcherArguments.Default()
    }

    @Test
    fun `EditorArguments decodes from minimal JSON`() {
        json.decodeFromString<EditorArguments>("""{"type":"arguments"}""") shouldBe EditorArguments.Default()
    }

    @Test
    fun `AppsArguments decodes from minimal JSON`() {
        json.decodeFromString<AppsArguments>("""{"type":"arguments"}""") shouldBe AppsArguments.Default()
    }

    @Test
    fun `DeveloperArguments decodes from minimal JSON`() {
        json.decodeFromString<DeveloperArguments>("""{"type":"arguments"}""") shouldBe DeveloperArguments.Default()
    }

    @Test
    fun `HistoryArguments decodes from minimal JSON`() {
        json.decodeFromString<HistoryArguments>("""{"type":"arguments"}""") shouldBe HistoryArguments.Default()
    }

    @Test
    fun `SaverArguments decodes from minimal JSON with identity`() {
        json.decodeFromString<SaverArguments>(
            """{"type":"default","sourceUris":["content://provider/file"]}"""
        ) shouldBe SaverArguments.Default(sourceUris = listOf("content://provider/file"))
    }

    @Test
    fun `SaverArguments without sourceUris fails instead of restoring an empty tab`() {
        shouldThrow<SerializationException> {
            json.decodeFromString<SaverArguments>("""{"type":"default"}""")
        }
    }

    @Test
    fun `AppDetailsArguments decodes from minimal JSON with identity`() {
        json.decodeFromString<AppDetailsArguments>(
            """{"packageName":"com.example.app"}"""
        ) shouldBe AppDetailsArguments(packageName = "com.example.app")
    }

    @Test
    fun `AppDetailsArguments without packageName fails instead of restoring an empty tab`() {
        shouldThrow<SerializationException> {
            json.decodeFromString<AppDetailsArguments>("""{}""")
        }
    }

    @Test
    fun `AppDetailsArguments tolerates legacy type field from old sessions`() {
        // Older versions serialized the `type` property (it had a backing field).
        json.decodeFromString<AppDetailsArguments>(
            """{"packageName":"com.example.app","type":"APP_DETAILS","initialTab":"PACKAGE_INFO"}"""
        ) shouldBe AppDetailsArguments(
            packageName = "com.example.app",
            initialTab = DetailTab.PACKAGE_INFO,
        )
    }

    @Test
    fun `AppDetailsArguments no longer serializes the type enum`() {
        val serialized = json.encodeToJsonElement(
            AppDetailsArguments(packageName = "com.example.app")
        )

        serialized.toString().toComparableJson() shouldBe """
            {
                "packageName": "com.example.app",
                "initialTab": "OVERVIEW"
            }
        """.toComparableJson()
    }
}
