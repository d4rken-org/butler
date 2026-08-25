package eu.darken.butler.common.storage.saf

/**
 * Providers we know enough about to open the system picker directly at their storage root.
 *
 * A stale [rootDocumentIdFor] degrades gracefully: it only feeds
 * [android.provider.DocumentsContract.EXTRA_INITIAL_URI], which the picker treats as a hint.
 */
enum class KnownStorageProvider(val packageNames: Set<String>) {
    TERMUX(setOf("com.termux")),
    ;

    fun authorityFor(pkg: String): String = "$pkg.documents"

    fun rootDocumentIdFor(pkg: String): String = when (this) {
        TERMUX -> "/data/data/$pkg/files/home"
    }

    companion object {
        fun forPackage(pkg: String): KnownStorageProvider? = entries.firstOrNull { pkg in it.packageNames }
    }
}
