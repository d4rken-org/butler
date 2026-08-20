package eu.darken.butler.main.core.external

import android.net.Uri

/** What an inbound ACTION_SEND / ACTION_SEND_MULTIPLE intent actually asks Butler to do. */
sealed interface ShareRoute {
    /** Text without any file attached; the editor takes it. */
    data class Text(val text: String, val subject: String?) : ShareRoute

    /**
     * One file, plus whatever the sender wrote about it. [caption] is shown next to the file
     * instead of being dropped, which is what makes preferring the stream safe.
     */
    data class SingleFile(val uri: Uri, val caption: String?) : ShareRoute

    /** Several files at once: nothing to decide per file, they go straight to the Saver. */
    data class MultipleFiles(val uris: List<Uri>) : ShareRoute

    data object Nothing : ShareRoute
}

/**
 * A stream wins over text. Apps routinely attach a description to a shared file, and taking the
 * text first meant the file itself was silently dropped in favour of that description.
 */
fun resolveShareRoute(text: String?, subject: String?, uris: List<Uri>): ShareRoute = when {
    uris.size == 1 -> ShareRoute.SingleFile(uris.first(), caption = text)
    uris.size > 1 -> ShareRoute.MultipleFiles(uris)
    text != null -> ShareRoute.Text(text, subject)
    else -> ShareRoute.Nothing
}
