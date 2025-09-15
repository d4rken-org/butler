package eu.darken.butler.main.core.motd

import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.locale.LocaleManager
import eu.darken.butler.common.locale.primary
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
class MotdRepo @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    private val endpoint: MotdEndpoint,
    private val settings: MotdSettings,
    generalSettings: GeneralSettings,
    localeManager: LocaleManager,
) {
    private val refreshTrigger = MutableStateFlow(Uuid.random())

    val motd: Flow<MotdState?> = combine(
        refreshTrigger,
        settings.isMotdEnabled.flow,
        generalSettings.isOnboardingCompleted.flow,
        localeManager.currentLocales,
    ) { _, isEnabled, isOnboardingCompleted, locales ->
        log(TAG, VERBOSE) { "isEnabled=$isEnabled, isOnboardingCompleted=$isOnboardingCompleted" }

        if (!isOnboardingCompleted) {
            log(TAG, INFO) { "Onboarding is not yet complete!" }
            return@combine null
        }
        if (!isEnabled) {
            log(TAG, INFO) { "MOTD is disabled." }
            return@combine null
        }
        try {
            val newMotd = endpoint.getMotd(locales.primary)
                ?.takeIf { it.motd.minimumVersion == null || BuildConfigWrap.VERSION_CODE >= it.motd.minimumVersion }
                ?.takeIf { it.motd.maximumVersion == null || BuildConfigWrap.VERSION_CODE <= it.motd.maximumVersion }

            settings.lastMotd.value(newMotd)
            log(TAG) { "New MOTD is $newMotd" }
            newMotd
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to retrieve MOTD" }
            null
        }
    }
        .onStart {
            emit(if (settings.isMotdEnabled.value()) settings.lastMotd.value() else null)
        }
        .flatMapLatest { motd -> settings.lastDismissedMotd.flow.map { motd to it } }
        .map { (motd, dismissedId) ->
            if (motd != null && motd.id != dismissedId) motd else null
        }
        .replayingShare(scope)

    suspend fun dismiss(id: Uuid) {
        log(TAG) { "dismiss(${id})" }
        settings.lastDismissedMotd.value(id)
    }

    companion object {
        private val TAG = logTag("Motd", "Repo")
    }
}