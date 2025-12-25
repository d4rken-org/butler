package eu.darken.butler.apps.core

import eu.darken.butler.apps.core.arguments.AppsArguments
import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class AppsArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = AppsArguments.Default()
        val serialized = json.encodeToJsonElement<AppsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments"
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with placeholder includes type discriminator`() {
        val args = AppsArguments.Default(
            placeholder = "test-placeholder",
        )
        val serialized = json.encodeToJsonElement<AppsArguments>(args)

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
                "placeholder": "my-apps"
            }
        """

        val args = json.decodeFromString<AppsArguments>(jsonString)

        args shouldBe AppsArguments.Default(
            placeholder = "my-apps",
        )
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = AppsArguments.Default(
            placeholder = "roundtrip-test",
        )

        val serialized = json.encodeToJsonElement<AppsArguments>(original)
        val deserialized = json.decodeFromString<AppsArguments>(serialized.toString())

        deserialized shouldBe original
    }
}
