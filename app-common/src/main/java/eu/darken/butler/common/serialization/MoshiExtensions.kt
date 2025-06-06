package eu.darken.butler.common.serialization

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okio.Buffer
import okio.BufferedSource
import okio.ByteString

inline fun <reified T> Moshi.fromJson(json: String): T =
    fromJson(Buffer().writeUtf8(json))

inline fun <reified T> Moshi.fromJson(source: BufferedSource): T =
    fromJson(JsonReader.of(source))

inline fun <reified T> Moshi.fromJson(reader: JsonReader): T =
    adapter<T>().fromJson(reader) ?: throw JsonDataException()

inline fun <reified T> Moshi.toJson(value: T): String =
    adapter<T>().toJson(value)

inline fun <reified T> JsonAdapter<T>.fromJson(json: ByteString): T? {
    return fromJson(json.utf8())
}

inline fun <reified T> JsonAdapter<T>.toByteString(value: T): ByteString {
    val buffer = Buffer()
    buffer.use {
        toJson(it, value)
    }
    return buffer.readByteString()
}
