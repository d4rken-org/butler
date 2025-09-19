package eu.darken.butler.main.core.release

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class ReleaseSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_release")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val releasePartyAt = dataStore.createValue("release.party.date", null as Instant?, json)
    val wantsBeta = dataStore.createValue("release.prerelease.consent", false)
    val earlyAdopter = dataStore.createValue("release.earlyadopter", null as Boolean?)

    override val mapper = PreferenceStoreMapper(

    )

    companion object {
        internal val TAG = logTag("Release", "Settings")
    }
}