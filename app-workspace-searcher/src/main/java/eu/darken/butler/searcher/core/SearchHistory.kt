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
        
        val entity = SearchHistoryEntity(
            baseQuery = query.query,
            rawQuery = converter.fromSearchQuery(query),
            searchedAt = Clock.System.now()
        )
        
        appScope.launch {
            // Insert new search
            searchHistoryDao.insert(entity)
            
            // Clean up old entries if exceeding max
            val maxItems = searcherSettings.maxHistoryItems.value()
            val currentCount = searchHistoryDao.getCount()
            if (currentCount > maxItems) {
                searchHistoryDao.deleteOldest(currentCount - maxItems)
            }
        }
        
        return entity.id
    }
    
    suspend fun updateResultCount(id: String, resultCount: Int) {
        log(TAG) { "Updating result count for $id: $resultCount" }
        searchHistoryDao.updateResultCount(id, resultCount)
    }
    
    fun getRecentSearches(limit: Int = 50): Flow<List<SearchHistoryItem>> {
        return searchHistoryDao.getRecentSearches(limit).map { entities ->
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
    
    fun getAllSearches(): Flow<List<SearchHistoryItem>> {
        return searchHistoryDao.getAllSearches().map { entities ->
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
    
    companion object {
        private val TAG = logTag("Searcher", "History")
    }
}