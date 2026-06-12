package eu.darken.butler.templates.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class TemplatesArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = TemplatesArguments.Default()
        val serialized = json.encodeToJsonElement<TemplatesArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "placeholder": ""
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with placeholder includes type discriminator`() {
        val args = TemplatesArguments.Default(
            placeholder = "test-placeholder",
        )
        val serialized = json.encodeToJsonElement<TemplatesArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "placeholder": "test-placeholder"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments",
                "placeholder": "my-template"
            }
        """

        val args = json.decodeFromString<TemplatesArguments>(jsonString)

        args shouldBe TemplatesArguments.Default(
            placeholder = "my-template",
        )
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = TemplatesArguments.Default(
            placeholder = "roundtrip-test",
        )

        val serialized = json.encodeToJsonElement<TemplatesArguments>(original)
        val deserialized = json.decodeFromString<TemplatesArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : TemplatesWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: TemplatesArguments): TemplatesWorkspace = error("unused")
        }
        val original = TemplatesArguments.Default(
            placeholder = "factory-test",
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<TemplatesArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
