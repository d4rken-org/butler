package eu.darken.butler.searcher.core.history.db

import androidx.room.TypeConverter
import eu.darken.butler.common.serialization.InstantSerializer
import eu.darken.butler.searcher.core.SearchQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

class SearchQueryConverter {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        serializersModule = SerializersModule {
            contextual(InstantSerializer)
        }
    }
    
    @TypeConverter
    fun fromSearchQuery(query: SearchQuery): String {
        return json.encodeToString(query)
    }
    
    @TypeConverter
    fun toSearchQuery(queryString: String): SearchQuery? {
        return try {
            json.decodeFromString<SearchQuery>(queryString)
        } catch (e: Exception) {
            // Handle deserialization failures gracefully
            null
        }
    }
}