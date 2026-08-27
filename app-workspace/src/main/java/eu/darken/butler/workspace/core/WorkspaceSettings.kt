package eu.darken.butler.workspace.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Singleton
class WorkspaceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    debugSettings: DebugSettings,
    json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "workspace_settings")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    val showTipBadgeExplanation = dataStore.createValue("workspace.manager.tip.badgeexplanation.show", true)

    val swipeGesturesEnabled = dataStore.createValue("workspace.swipe.gestures.enabled", true)

    val onDemandWorkspaceCreation = dataStore.createValue("workspace.swipe.ondemand.enabled", true)

    val livePreview = dataStore.createValue("workspace.preview.live.enabled", true)

    val layoutModePortrait = dataStore.createValue("workspace.layout.mode.portrait", WorkspacePanelMode.AUTO, json)

    val layoutModeLandscape = dataStore.createValue("workspace.layout.mode.landscape", WorkspacePanelMode.AUTO, json)

    val paneClickToFocus = dataStore.createValue("workspace.pane.clicktofocus.enabled", true)

    val sessionRestoreEnabled = dataStore.createValue("workspace.session.restore.enabled", true)

    val autoPauseEnabled = dataStore.createValue("workspace.session.autopause.enabled", true)

    val autoPauseIdleTimeout = dataStore.createValue(
        "workspace.session.autopause.timeout",
        AUTO_PAUSE_IDLE_TIMEOUT_DEFAULT,
        json,
    )

    override val mapper = PreferenceStoreMapper(
        debugSettings.isDebugMode,
        showTipBadgeExplanation,
        swipeGesturesEnabled,
        onDemandWorkspaceCreation,
        livePreview,
        layoutModePortrait,
        layoutModeLandscape,
        paneClickToFocus,
        sessionRestoreEnabled,
        autoPauseEnabled,
        autoPauseIdleTimeout,
    )

    companion object {
        internal val TAG = logTag("Workspace", "Settings")

        val AUTO_PAUSE_IDLE_TIMEOUT_DEFAULT = 2.hours
        val AUTO_PAUSE_IDLE_TIMEOUT_MIN = 15.minutes
        val AUTO_PAUSE_IDLE_TIMEOUT_MAX = 12.hours

        /**
         * Keeps a debug-edited or legacy stored value from producing a nonsense threshold (pausing
         * everything a second after it leaves the screen, or effectively never).
         */
        fun clampIdleTimeout(value: Duration): Duration =
            value.coerceIn(AUTO_PAUSE_IDLE_TIMEOUT_MIN, AUTO_PAUSE_IDLE_TIMEOUT_MAX)
    }
}