package eu.darken.butler.common.serialization

import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class RegexAdapterTest : BaseTest() {

    val json = Json {
        serializersModule = SerializersModule {
            contextual(RegexSerializer)
        }
    }

    @Serializable(with = TestContainer.Serializer::class)
    data class TestContainer(
        val regexValue: Regex?,
        val regexList: List<Regex>
    ) {
        @OptIn(ExperimentalSerializationApi::class)
        object Serializer : KSerializer<TestContainer> {
            override val descriptor = buildClassSerialDescriptor("TestContainer") {
                element("regexValue", RegexSerializer.descriptor, isOptional = true)
                element("regexList", ListSerializer(RegexSerializer).descriptor)
            }

            override fun serialize(encoder: Encoder, value: TestContainer) {
                encoder.encodeStructure(descriptor) {
                    encodeNullableSerializableElement(descriptor, 0, RegexSerializer, value.regexValue)
                    encodeSerializableElement(descriptor, 1, ListSerializer(RegexSerializer), value.regexList)
                }
            }

            override fun deserialize(decoder: Decoder): TestContainer {
                return decoder.decodeStructure(descriptor) {
                    var regexValue: Regex? = null
                    var regexList: List<Regex> = emptyList()

                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            0 -> regexValue = decodeNullableSerializableElement(descriptor, 0, RegexSerializer)
                            1 -> regexList = decodeSerializableElement(descriptor, 1, ListSerializer(RegexSerializer))
                            CompositeDecoder.DECODE_DONE -> break
                            else -> error("Unexpected index: $index")
                        }
                    }

                    TestContainer(regexValue, regexList)
                }
            }
        }
    }

    @Test
    fun `serialize test container`() {
        val before = TestContainer(
            regexValue = Regex("value", RegexOption.LITERAL),
            regexList = listOf(
                Regex("ele1", RegexOption.COMMENTS),
                Regex("ele2", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)),
            )
        )

        val rawJson = json.encodeToString(TestContainer.serializer(), before)

        rawJson.toComparableJson() shouldBe """
            {
                "regexValue": {
                    "pattern": "value",
                    "options": [
                        "LITERAL"
                    ]
                },
                "regexList": [
                    {
                        "pattern": "ele1",
                        "options": [
                            "COMMENTS"
                        ]
                    },
                    {
                        "pattern": "ele2",
                        "options": [
                            "MULTILINE",
                            "DOT_MATCHES_ALL"
                        ]
                    }
                ]
            }
        """.toComparableJson()

        val after = json.decodeFromString(TestContainer.serializer(), rawJson)
        after.regexValue!!.apply {
            pattern shouldBe before.regexValue!!.pattern
            options shouldBe before.regexValue.options
        }
        after.regexList[0].apply {
            pattern shouldBe before.regexList[0].pattern
            options shouldBe before.regexList[0].options
        }
        after.regexList[1].apply {
            pattern shouldBe before.regexList[1].pattern
            options shouldBe before.regexList[1].options
        }

    }
}