package eu.darken.butler.developer.core

import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.developer.DeveloperArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class DeveloperArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = DeveloperArguments.Default()
        val serialized = json.encodeToJsonElement<DeveloperArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "placeholder": ""
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments",
                "placeholder": "dev-tools"
            }
        """

        val args = json.decodeFromString<DeveloperArguments>(jsonString)

        args shouldBe DeveloperArguments.Default(
            placeholder = "dev-tools",
        )
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = DeveloperArguments.Default(
            placeholder = "roundtrip-test",
        )

        val serialized = json.encodeToJsonElement<DeveloperArguments>(original)
        val deserialized = json.decodeFromString<DeveloperArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : DeveloperWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: DeveloperArguments): DeveloperWorkspace = error("unused")
        }
        val original = DeveloperArguments.Default(
            placeholder = "factory-test",
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<DeveloperArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
