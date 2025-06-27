package testhelpers.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encode
import okio.buffer
import okio.sink
import java.io.File

/**
 * Converts a JSON string to a comparable format for testing.
 * This normalizes the JSON by parsing and re-serializing it with sorted keys.
 */
fun String.toComparableJson(): String {
    val jsonElement = Json.parseToJsonElement(this)
    return jsonElement.toComparableJsonElement().toString()
}

/**
 * Recursively sorts JSON objects by their keys to make them comparable.
 */
private fun JsonElement.toComparableJsonElement(): JsonElement {
    return when (this) {
        is JsonObject -> {
            val sortedEntries = entries.sortedBy { it.key }.associate { (key, value) ->
                key to value.toComparableJsonElement()
            }
            JsonObject(sortedEntries)
        }
        is JsonArray -> {
            JsonArray(map { it.toComparableJsonElement() })
        }
        else -> this // JsonPrimitive or JsonNull
    }
}

/**
 * Pretty prints a JSON string for debugging purposes.
 */
fun String.prettyPrintJson(): String {
    val json = Json { prettyPrint = true }
    val jsonElement = Json.parseToJsonElement(this)
    return json.encodeToString(JsonElement.serializer(), jsonElement)
}

fun String.writeToFile(file: File) = encode().let { text ->
    require(!file.exists())
    file.parentFile?.mkdirs()
    file.createNewFile()
    file.sink().buffer().use { it.write(text) }
}