package eu.darken.butler.upgrade.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.createValue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val Context.dataStore by preferencesDataStore(name = "settings_gplay")

    private val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val lastProStateAt = dataStore.createValue("gplay.cache.lastProAt", 0L)
    val lastProStateSku = dataStore.createValue("gplay.cache.lastProSku", "")

    // Start of the current "we should be Pro but Play won't confirm it" episode (0 = no episode /
    // confirmed). Distinct from lastProStateAt (last CONFIRMED pro): the diagnostics UI promises
    // "24h in THIS episode", so it must measure from when confirmation was lost, not last success.
    val proUnconfirmedSince = dataStore.createValue("gplay.cache.proUnconfirmedAt", 0L)

    // A confirmed Pro purchase: record SKU + timestamp AND close any open unconfirmed episode in ONE
    // transaction, so a crash between writes can't leave a stale episode pointing past a fresh
    // confirmation. SKU only modifies the grace-window length; the timestamp gates grace.
    suspend fun stampProConfirmed(skuId: String, at: Long) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(SKU_KEY)] = skuId
            prefs[longPreferencesKey(LAST_PRO_KEY)] = at
            prefs[longPreferencesKey(UNCONFIRMED_KEY)] = 0L
        }
    }

    // Set-if-unset episode start, guarded so a stale/buffered failure can't reopen an episode a newer
    // confirmation already closed: only starts when no episode is open AND the failure occurred after
    // the last confirmation (which itself must exist). Preserves an already-open episode's start.
    suspend fun startUnconfirmedEpisode(occurredAt: Long) {
        context.dataStore.edit { prefs ->
            val lastPro = prefs[longPreferencesKey(LAST_PRO_KEY)] ?: 0L
            val existing = prefs[longPreferencesKey(UNCONFIRMED_KEY)] ?: 0L
            if (existing == 0L && lastPro > 0L && occurredAt > lastPro) {
                prefs[longPreferencesKey(UNCONFIRMED_KEY)] = occurredAt
            }
        }
    }

    companion object {
        private const val LAST_PRO_KEY = "gplay.cache.lastProAt"
        private const val SKU_KEY = "gplay.cache.lastProSku"
        private const val UNCONFIRMED_KEY = "gplay.cache.proUnconfirmedAt"
    }
}
