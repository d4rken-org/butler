package eu.darken.butler.common.storage.saf

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

data class StorageProviderSuggestion(
    val packageName: String,
    val authority: String,
    val label: String,
    /** Non-null means the picker can be opened directly at this app's storage root. */
    val known: KnownStorageProvider?,
)

/**
 * Finds installed apps that expose storage through a documents provider, so they can be offered
 * as add-storage shortcuts instead of leaving the user to find them in the system picker.
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
            .groupBy { it.packageName }
            .map { (_, candidates) -> candidates.preferred() }
            .sortedWith(compareBy({ it.known == null }, { it.label.lowercase() }))
            .also { log(TAG) { "getSuggestions(): $it" } }
    }

    /**
     * Label of the app owning [authority], or null when that app would not be suggested either.
     *
     * Used to pre-fill the location name after a grant, so an ordinary folder picked through the
     * system picker keeps its path-derived default name instead of being labelled after the
     * platform provider that served it.
     */
    suspend fun labelForAuthority(authority: String): String? = withContext(dispatcherProvider.IO) {
        try {
            context.packageManager.resolveContentProvider(authority, 0)?.toSuggestion()?.label
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to resolve $authority: ${e.asLog()}" }
            null
        }
    }

    private fun ProviderInfo.toSuggestion(): StorageProviderSuggestion? {
        val authority = authority ?: return null
        if (!exported) return null
        if (packageName == context.packageName) return null
        if (PLATFORM_AUTHORITIES.contains(authority)) return null
        // Platform and system components register providers an authority list cannot keep up with,
        // and none of them has a launcher entry the user could act on.
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return null

        return StorageProviderSuggestion(
            packageName = packageName,
            authority = authority,
            label = context.packageManager.getApplicationLabel(applicationInfo).toString(),
            known = KnownStorageProvider.forPackage(packageName),
        )
    }

    /** An app may declare several providers, prefer the one we can deep-link into. */
    private fun List<StorageProviderSuggestion>.preferred(): StorageProviderSuggestion =
        firstOrNull { it.known != null && it.authority == it.known.authorityFor(it.packageName) } ?: first()

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
