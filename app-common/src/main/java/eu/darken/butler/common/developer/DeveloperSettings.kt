package eu.darken.butler.common.developer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.datastore.createValue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeveloperSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val Context.dataStore by preferencesDataStore(name = "developer_settings")

    private val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val isDeveloperModeUnlocked = dataStore.createValue(
        "developer.mode.unlocked",
        BuildConfigWrap.DEBUG,
    )
}
