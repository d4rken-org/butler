package eu.darken.butler.common.updater

import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.getPackageInfo
import eu.darken.butler.common.pkgs.features.getInstallerInfo
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Reusable
class FossUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: GithubReleaseCheck,
    private val webpageTool: WebpageTool,
    private val settings: FossUpdateSettings,
) : UpdateChecker {

    override suspend fun getLatest(channel: UpdateChecker.Channel): UpdateChecker.Update? {
        log(TAG) { "getLatest($channel) checking..." }

        val release: GithubApi.ReleaseInfo? = try {
            if ((settings.lastReleaseCheck.value() - Clock.System.now()) < UPDATE_CHECK_INTERVAL) {
                log(TAG) { "Using cached release data" }
                when (channel) {
                    UpdateChecker.Channel.BETA -> settings.lastReleaseBeta.value()
                    UpdateChecker.Channel.PROD -> settings.lastReleaseProd.value()
                }
            } else {
                log(TAG) { "Fetching new release data" }
                when (channel) {
                    UpdateChecker.Channel.BETA -> checker.allReleases(OWNER, REPO).first()
                    UpdateChecker.Channel.PROD -> checker.latestRelease(OWNER, REPO)
                }.also {
                    log(TAG, INFO) { "getLatest($channel) new data is $it" }
                    settings.lastReleaseCheck.value(Clock.System.now())
                    when (channel) {
                        UpdateChecker.Channel.BETA -> settings.lastReleaseBeta.value(it)
                        UpdateChecker.Channel.PROD -> settings.lastReleaseProd.value(it)
                    }
                }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "getLatest($channel) failed: ${e.asLog()}" }
            null
        }

        log(TAG, INFO) { "getLatest($channel) is ${release?.tagName}" }

        val update = release?.let { rel ->
            Update(
                channel = channel,
                versionName = rel.tagName,
                changelogLink = rel.htmlUrl,
                downloadLink = rel.assets.singleOrNull { it.name.endsWith(".apk") }?.downloadUrl,
            )
        }

        return update
    }

    override suspend fun startUpdate(update: UpdateChecker.Update) {
        log(TAG, INFO) { "startUpdate($update)" }
        update as Update
        if (update.downloadLink != null) {
            webpageTool.open(update.downloadLink)
        } else {
            log(TAG, WARN) { "No download link available for $update" }
        }
    }

    override suspend fun viewUpdate(update: UpdateChecker.Update) {
        log(TAG, INFO) { "viewUpdate($update)" }
        update as Update
        webpageTool.open(update.changelogLink)
    }

    override suspend fun dismissUpdate(update: UpdateChecker.Update) {
        log(TAG, INFO) { "dismissUpdate($update)" }
        update as Update
        settings.dismiss(update)
    }

    override suspend fun isDismissed(update: UpdateChecker.Update): Boolean {
        update as Update
        return settings.isDismissed(update)
    }

    override fun isEnabledByDefault(): Boolean {
        val pm = context.packageManager
        val installers: Set<String> = context.getPackageInfo()
            .getInstallerInfo(pm)
            .allInstallers
            .map { it.id.name }
            .toSet()

        val isEnabled = when {
            installers.any { it.startsWith("org.fdroid.fdroid") } -> {
                false
            }

            installers.any { FDROIDS.contains(it) } -> {
                false
            }

            else -> true
        }
        log(TAG, INFO) { "Update check default isEnabled=$isEnabled, installers: $installers" }
        return isEnabled
    }

    override suspend fun isCheckSupported(): Boolean {
        return true
    }

    data class Update(
        override val channel: UpdateChecker.Channel,
        override val versionName: String,
        val changelogLink: String,
        val downloadLink: String?,
    ) : UpdateChecker.Update

    companion object {
        private val UPDATE_CHECK_INTERVAL = 3.days
        private const val OWNER = "d4rken-org"
        private const val REPO = "butler"
        private val FDROIDS = setOf(
            "org.fdroid.fdroid",
            "com.machiav3lli.fdroid",
            "com.looker.droidify",
            "dev.imranr.obtainium",
            "com.aurora.store",
            "in.sunilpaulmathew.izzyondroid",
            "eu.bubu1.fdroidclassic",
            "org.gdroid.gdroid",
        )
        private val TAG = logTag("Updater", "Checker", "FOSS")
    }
}