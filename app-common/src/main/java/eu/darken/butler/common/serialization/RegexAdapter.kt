package eu.darken.butler.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object RegexSerializer : KSerializer<Regex> {
    override val descriptor: SerialDescriptor = Wrapper.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Regex) {
        val wrapper = Wrapper(
            pattern = value.pattern,
            options = value.options.map { it.toWrapperOption() }.toSet(),
        )
        encoder.encodeSerializableValue(Wrapper.serializer(), wrapper)
    }

    override fun deserialize(decoder: Decoder): Regex {
        val wrapper = decoder.decodeSerializableValue(Wrapper.serializer())
        return Regex(
            pattern = wrapper.pattern,
            options = wrapper.options.map { it.toRegexOption() }.toSet()
        )
    }

    private fun Wrapper.Option.toRegexOption() = when (this) {
        Wrapper.Option.IGNORE_CASE -> RegexOption.IGNORE_CASE
        Wrapper.Option.MULTILINE -> RegexOption.MULTILINE
        Wrapper.Option.LITERAL -> RegexOption.LITERAL
        Wrapper.Option.UNIX_LINES -> RegexOption.UNIX_LINES
        Wrapper.Option.COMMENTS -> RegexOption.COMMENTS
        Wrapper.Option.DOT_MATCHES_ALL -> RegexOption.DOT_MATCHES_ALL
        Wrapper.Option.CANON_EQ -> RegexOption.CANON_EQ
    }

    private fun RegexOption.toWrapperOption() = when (this) {
        RegexOption.IGNORE_CASE -> Wrapper.Option.IGNORE_CASE
        RegexOption.MULTILINE -> Wrapper.Option.MULTILINE
        RegexOption.LITERAL -> Wrapper.Option.LITERAL
        RegexOption.UNIX_LINES -> Wrapper.Option.UNIX_LINES
        RegexOption.COMMENTS -> Wrapper.Option.COMMENTS
        RegexOption.DOT_MATCHES_ALL -> Wrapper.Option.DOT_MATCHES_ALL
        RegexOption.CANON_EQ -> Wrapper.Option.CANON_EQ
    }

    @Serializable
    data class Wrapper(
        @SerialName("pattern") val pattern: String,
        @SerialName("options") val options: Set<Option>
    ) {
        @Serializable
        enum class Option {
            @SerialName("IGNORE_CASE") IGNORE_CASE,
            @SerialName("MULTILINE") MULTILINE,
            @SerialName("LITERAL") LITERAL,
            @SerialName("UNIX_LINES") UNIX_LINES,
            @SerialName("COMMENTS") COMMENTS,
            @SerialName("DOT_MATCHES_ALL") DOT_MATCHES_ALL,
            @SerialName("CANON_EQ") CANON_EQ,
        }
    }
}