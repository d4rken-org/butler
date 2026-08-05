package eu.darken.butler.common.review

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlin.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_review_gplay")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    // Pinned loud: unreadable data has to surface instead of silently reading as "never reviewed",
    // which would re-ask a user who already left a review.
    val lastDismissed = dataStore.createValue(
        "review.dismissedAt",
        null as Instant?,
        json,
        onErrorFallbackToDefault = false,
    )
    val reviewedAt = dataStore.createValue(
        "review.reviewedAt",
        null as Instant?,
        json,
        onErrorFallbackToDefault = false,
    )

    override val mapper = PreferenceStoreMapper(

    )

    companion object {
        internal val TAG = logTag("Review", "Settings", "Gplay")
    }
}