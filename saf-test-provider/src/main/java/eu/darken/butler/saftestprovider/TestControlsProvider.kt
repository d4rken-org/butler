package eu.darken.butler.saftestprovider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class TestControlsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && uid != SHELL_UID) {
            throw SecurityException("Fixture controls require shell or self UID")
        }
        require(method in CONTROL_METHODS) { "Unknown fixture control: $method" }

        // Acquire and call the local provider as self, after authenticating the external caller.
        val identity = Binder.clearCallingIdentity()
        try {
            val resolver = requireNotNull(context).contentResolver
            return checkNotNull(resolver.acquireContentProviderClient(TestDocumentsProvider.AUTHORITY)).use { client ->
                val provider = checkNotNull(client.localContentProvider) { "Documents provider must be in-process" }
                provider.call(method, arg, extras)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = throw UnsupportedOperationException("Use call() for fixture controls")

    override fun getType(uri: Uri): String = throw UnsupportedOperationException("Use call() for fixture controls")

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Use call() for fixture controls")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use call() for fixture controls")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use call() for fixture controls")

    companion object {
        const val AUTHORITY = "eu.darken.butler.saftestprovider.controls"
        private const val SHELL_UID = 2000
        private val CONTROL_METHODS = setOf(
            "reset", "setLoading", "completeLoading", "setError", "clearError", "makeDuplicate", "stats",
        )
    }
}
