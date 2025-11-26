package eu.darken.butler.common.trash

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

@Singleton
class TrashSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_trash")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val enabled = dataStore.createValue("trash.enabled", false)

    val expiresAfter = dataStore.createValue("trash.expiration.duration", 30.days, json)

    val maxTrashSize = dataStore.createValue("trash.storage.maxsize", 524_288_000L)

    override val mapper = PreferenceStoreMapper(
        enabled,
        expiresAfter,
        maxTrashSize,
    )
}