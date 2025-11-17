package eu.darken.butler.workspace.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WorkspaceSessionManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @Named("workspace_session") private val sessionDataStore: DataStore<Preferences>,
    private val workspaceSettings: WorkspaceSettings,
    private val json: Json,
) {
    private val tag = logTag("Workspace", "SessionManager")

    private val _restorationState = MutableStateFlow(RestorationState.IDLE)
    val restorationState: StateFlow<RestorationState> = _restorationState.asStateFlow()

    private val sessionKey = stringPreferencesKey("workspace_session_v1")

    /**
     * Save the current workspace session
     */
    suspend fun saveSession(workspaces: List<WorkspaceSessionData>) {
        if (!workspaceSettings.sessionRestoreEnabled.value()) {
            log(tag, DEBUG) { "Session restoration disabled, not saving session" }
            return
        }

        try {
            val session = WorkspaceSession(
                version = CURRENT_SESSION_VERSION,
                timestamp = System.currentTimeMillis().toString(), // Using millis as string for now
                workspaces = workspaces,
            )

            val serialized = json.encodeToString(session)
            sessionDataStore.edit { preferences ->
                preferences[sessionKey] = serialized
            }

            log(tag, INFO) { "Saved session with ${workspaces.size} workspaces" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save session: ${e.asLog()}" }
        }
    }

    /**
     * Load the saved workspace session
     */
    suspend fun loadSession(): WorkspaceSession? {
        if (!workspaceSettings.sessionRestoreEnabled.value()) {
            log(tag, DEBUG) { "Session restoration disabled, not loading session" }
            return null
        }

        return try {
            val serialized = sessionDataStore.data.first()[sessionKey]
            if (serialized != null) {
                val session = json.decodeFromString<WorkspaceSession>(serialized)

                // Check version compatibility
                if (session.version > CURRENT_SESSION_VERSION) {
                    log(tag, WARN) { "Session version ${session.version} is newer than current version $CURRENT_SESSION_VERSION" }
                    return null
                }

                log(tag, INFO) { "Loaded session with ${session.workspaces.size} workspaces from version ${session.version}" }

                // Migrate if needed
                if (session.version < CURRENT_SESSION_VERSION) {
                    return migrateSession(session)
                }

                session
            } else {
                log(tag, DEBUG) { "No saved session found" }
                null
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to load session: ${e.asLog()}" }
            null
        }
    }

    /**
     * Clear the saved session
     */
    suspend fun clearSession() {
        try {
            sessionDataStore.edit { preferences ->
                preferences.remove(sessionKey)
            }
            log(tag, INFO) { "Cleared saved session" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to clear session: ${e.asLog()}" }
        }
    }

    /**
     * Update restoration state
     */
    fun setRestorationState(state: RestorationState) {
        _restorationState.value = state
    }

    /**
     * Check if restoration is enabled
     */
    suspend fun isRestorationEnabled(): Boolean = workspaceSettings.sessionRestoreEnabled.value()

    /**
     * Check if we have a stored session
     */
    suspend fun hasStoredSession(): Boolean {
        return try {
            sessionDataStore.data.first()[sessionKey] != null
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to check for stored session: ${e.asLog()}" }
            false
        }
    }

    private suspend fun migrateSession(session: WorkspaceSession): WorkspaceSession {
        log(tag, INFO) { "Migrating session from version ${session.version} to $CURRENT_SESSION_VERSION" }

        // Add migration logic here as needed for future versions
        return when (session.version) {
            1 -> session.copy(version = CURRENT_SESSION_VERSION)
            else -> session
        }
    }

    enum class RestorationState {
        IDLE,
        RESTORING,
        RESTORED,
        FAILED
    }

    companion object {
        const val CURRENT_SESSION_VERSION = 1
    }
}