package eu.darken.butler.searcher.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.searcher.core.db.SearchHistoryDao
import eu.darken.butler.searcher.core.db.SearchHistoryEntity
import eu.darken.butler.searcher.core.db.SearchQueryConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistory @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val searchHistoryDao: SearchHistoryDao,
    private val searcherSettings: SearcherSettings
) {
    
    data class SearchHistoryItem(
        val id: String,
        val baseQuery: String,
        val searchQuery: SearchQuery?,
        val searchedAt: Instant,
        val resultCount: Int? = null
    )
    
    private val converter = SearchQueryConverter()
    
    suspend fun addSearch(query: SearchQuery): String {
        log(TAG, INFO) { "Adding search to history: ${query.query}" }

        val now = Clock.System.now()

        // Check if we have a recent identical search (within last 5 minutes)
        val existingEntry = searchHistoryDao.getLatestByQuery(query.query)

        val entityId = if (existingEntry != null) {
            val timeDiff = now - existingEntry.searchedAt
            if (timeDiff.inWholeMinutes < 5) {
                // Update existing entry's timestamp instead of creating new one
                log(TAG) { "Updating timestamp for existing search: ${query.query}" }
                searchHistoryDao.updateTimestamp(existingEntry.id, now)
                existingEntry.id
            } else {
                // More than 5 minutes old, create new entry
                createNewSearchEntry(query, now)
            }
        } else {
            // No existing entry, create new one
            createNewSearchEntry(query, now)
        }

        appScope.launch {
            // Clean up old entries if exceeding max
            val maxItems = searcherSettings.maxHistoryItems.value()
            val currentCount = searchHistoryDao.getCount()
            if (currentCount > maxItems) {
                searchHistoryDao.deleteOldest(currentCount - maxItems)
            }
        }

        return entityId
    }

    private suspend fun createNewSearchEntry(query: SearchQuery, timestamp: kotlin.time.Instant): String {
        val entity = SearchHistoryEntity(
            baseQuery = query.query,
            rawQuery = converter.fromSearchQuery(query),
            searchedAt = timestamp
        )
        searchHistoryDao.insert(entity)
        return entity.id
    }
    
    suspend fun updateResultCount(id: String, resultCount: Int) {
        log(TAG) { "Updating result count for $id: $resultCount" }
        searchHistoryDao.updateResultCount(id, resultCount)
    }
    
    fun getSearches(limit: Int? = 50): Flow<List<SearchHistoryItem>> {
        val searches = if(limit != null) {
            searchHistoryDao.getRecentSearches(limit)
        } else {
            searchHistoryDao.getAllSearches()
        }
        return searches.map { entities ->
            entities.map { entity ->
                SearchHistoryItem(
                    id = entity.id,
                    baseQuery = entity.baseQuery,
                    searchQuery = converter.toSearchQuery(entity.rawQuery),
                    searchedAt = entity.searchedAt,
                    resultCount = entity.resultCount
                )
            }
        }
    }
    
    suspend fun removeItem(id: String) {
        log(TAG) { "Removing history item: $id" }
        searchHistoryDao.deleteById(id)
    }
    
    suspend fun clearHistory() {
        log(TAG) { "Clearing all search history" }
        searchHistoryDao.deleteAll()
    }

    suspend fun getHistoryCount(): Int {
        return searchHistoryDao.getCount()
    }
    
    companion object {
        private val TAG = logTag("Searcher", "History")
    }
}