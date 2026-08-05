package eu.darken.butler.common.tour

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.serialization.SerializationCommon
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TourSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    @SerializationCommon private val json: Json,
) {
    private val Context.dataStore by preferencesDataStore(name = "tour_settings")

    private val dataStore: DataStore<Preferences>
        get() = context.dataStore

    /** Master switch. Flipped off by "Disable all tours", back on by "Reset guided tours". */
    val isGuidedToursEnabled = dataStore.createValue("tour.enabled", true)

    /**
     * Per-tour completed/dismissed state.
     *
     * [createValue]'s `onErrorFallbackToDefault` follows the same convention as `GeneralSettings`'
     * serialized values: a malformed stored value must not throw in release, because
     * `DataStoreValue.update` decodes the old value before writing — both `shouldStart()` and
     * `reset()` would throw and leave tours permanently broken with no recovery path.
     */
    val tourPreferences = dataStore.createValue(
        key = "tour.preferences",
        defaultValue = TourPreferences(),
        json = json,
        onErrorFallbackToDefault = BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE,
    )
}
