package eu.darken.butler.common.storage.saf

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getPackageInfo2
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * A launchable third-party app that serves storage through a documents provider.
 *
 * [lastUpdateTime] is what tells an icon cached before an app update from the current one.
 */
data class StorageProviderApp(
    override val packageName: String,
    val appLabel: String,
    val lastUpdateTime: Long,
) : Pkg {
    override val id: Pkg.Id get() = Pkg.Id(packageName)
    override val label: CaString get() = appLabel.toCaString()
    override val icon: CaDrawable? get() = null
}

data class StorageProviderSuggestion(
    val app: StorageProviderApp,
    val authority: String,
    val known: KnownStorageProvider,
) {
    val packageName: String get() = app.packageName
    val label: String get() = app.appLabel
}

/**
 * Finds installed apps whose storage the system picker can be opened at directly, so they can be
 * offered as add-storage shortcuts.
 *
 * Only curated providers qualify: whether a provider's roots can be picked as a folder at all is
 * only visible to the picker itself, so an uncurated app would be offered without knowing if the
 * picker can even show it.
 */
@Reusable
class StorageProviderSuggester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun getSuggestions(): List<StorageProviderSuggestion> = withContext(dispatcherProvider.IO) {
        val resolved = try {
            context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to query documents providers: ${e.asLog()}" }
            return@withContext emptyList()
        }

        resolved
            .mapNotNull { info ->
                val providerInfo = info.providerInfo ?: return@mapNotNull null
                try {
                    providerInfo.toSuggestion()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, WARN) { "Skipping ${providerInfo.authority}: ${e.asLog()}" }
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .also { log(TAG) { "getSuggestions(): $it" } }
    }

    /**
     * The app owning [authority], or null for platform providers and apps without a launcher entry.
     *
     * Lets a location granted through the system picker be labelled and iconed after the app that
     * serves it, while an ordinary folder keeps its path-derived default name instead of being
     * labelled after the platform provider.
     */
    suspend fun appForAuthority(authority: String): StorageProviderApp? = withContext(dispatcherProvider.IO) {
        try {
            context.packageManager.resolveContentProvider(authority, 0)?.toApp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to resolve $authority: ${e.asLog()}" }
            null
        }
    }

    private fun ProviderInfo.toApp(): StorageProviderApp? {
        val authority = authority ?: return null
        if (!exported) return null
        if (packageName == context.packageName) return null
        if (PLATFORM_AUTHORITIES.contains(authority)) return null
        // Platform and system components register providers an authority list cannot keep up with,
        // and none of them has a launcher entry the user could act on.
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return null

        val packageInfo = context.packageManager.getPackageInfo2(Pkg.Id(packageName)) ?: return null

        return StorageProviderApp(
            packageName = packageName,
            appLabel = context.packageManager.getApplicationLabel(applicationInfo).toString(),
            lastUpdateTime = packageInfo.lastUpdateTime,
        )
    }

    /** An app may declare several providers, only the one we can deep-link into is a suggestion. */
    private fun ProviderInfo.toSuggestion(): StorageProviderSuggestion? {
        val known = KnownStorageProvider.forPackage(packageName) ?: return null
        if (authority != known.authorityFor(packageName)) return null
        val app = toApp() ?: return null

        return StorageProviderSuggestion(app = app, authority = authority, known = known)
    }

    companion object {
        private val TAG = logTag("SAF", "ProviderSuggester")

        /**
         * Keyed on authority, not package: platform package names drift across versions and OEMs
         * (`com.android.providers.media` became `com.android.providers.media.module`).
         */
        private val PLATFORM_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            "com.android.mtp.documents",
            // Browses inside archive files for the system picker, publishes no grantable root.
            "com.android.documentsui.archives",
        )
    }
}
