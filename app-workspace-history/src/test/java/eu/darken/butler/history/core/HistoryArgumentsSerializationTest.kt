package eu.darken.butler.history.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class HistoryArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = HistoryArguments.Default()
        val serialized = json.encodeToJsonElement<HistoryArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filter": {
                    "outcomes": [],
                    "kinds": [],
                    "pathScopes": []
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments",
                "filter": {
                    "outcomes": ["FAILED"],
                    "kinds": ["DELETE"],
                    "pathScopes": ["/sdcard/DCIM"]
                }
            }
        """

        val args = json.decodeFromString<HistoryArguments>(jsonString)

        args shouldBe HistoryArguments.Default(
            filter = HistoryFilter(
                outcomes = setOf(HistoryOutcome.FAILED),
                kinds = setOf(Operation.Metadata.Kind.DELETE),
                pathScopes = setOf("/sdcard/DCIM"),
            ),
        )
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = HistoryArguments.Default(
            filter = HistoryFilter(
                outcomes = setOf(HistoryOutcome.COMPLETED, HistoryOutcome.PARTIAL),
                kinds = setOf(Operation.Metadata.Kind.COPY),
                pathScopes = setOf("/sdcard/Download"),
            ),
        )

        val serialized = json.encodeToJsonElement<HistoryArguments>(original)
        val deserialized = json.decodeFromString<HistoryArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `focus entry survives a roundtrip`() {
        val original = HistoryArguments.Default(focusEntryId = "op-1")

        val serialized = json.encodeToJsonElement<HistoryArguments>(original)

        json.decodeFromString<HistoryArguments>(serialized.toString()) shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : HistoryWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: HistoryArguments): HistoryWorkspace = error("unused")
        }
        val original = HistoryArguments.Default(
            filter = HistoryFilter(
                outcomes = setOf(HistoryOutcome.FAILED),
                pathScopes = setOf("/sdcard/DCIM"),
            ),
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<HistoryArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
