package eu.darken.butler.common

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure Kotlin URI implementation for SAF (Storage Access Framework) operations.
 *
 * This class provides a framework-independent alternative to `android.net.Uri`,
 * enabling SAF operations to be tested without Android dependencies (Robolectric).
 *
 * ## Purpose
 *
 * - **Testability**: Pure Kotlin implementation allows fast JVM unit tests
 * - **Domain Purity**: Core domain types (like SAFPath) remain framework-independent
 * - **Architecture**: Maintains clean separation between domain logic and Android framework
 *
 * ## SAF URI Structure
 *
 * SAF URIs follow the pattern:
 * ```
 * content://com.android.externalstorage.documents/tree/primary%3Apath%2Fto%2Ffile
 * └─────┘ └──────────────────────────────────────┘ └──┘ └────────────────────────┘
 * scheme            authority                      type      encoded path
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Parse from string
 * val uri = SafUri.parse("content://authority/tree/primary%3Afolder")
 * println(uri.pathSegments) // ["tree", "primary", "folder"]
 *
 * // Convert to/from Android Uri at boundaries
 * val androidUri: android.net.Uri = uri.toAndroidUri()
 * val safUri = SafUri.fromAndroidUri(androidUri)
 * ```
 *
 * ## Boundary Usage
 *
 * SafUri is used internally in domain types. Convert to Android Uri only at Android API boundaries:
 * - ContentResolver operations
 * - Intent creation
 * - DocumentsContract calls
 *
 * @property rawUri The complete URI string
 */
@Serializable
data class SafUri internal constructor(
    val rawUri: String,
) {
    /**
     * URI scheme (e.g., "content", "file")
     */
    val scheme: String?
        get() = rawUri.substringBefore(":", "").takeIf { it.isNotEmpty() }

    /**
     * URI authority (e.g., "com.android.externalstorage.documents")
     */
    val authority: String?
        get() {
            if (!rawUri.contains("://")) return null
            val afterScheme = rawUri.substringAfter("://")
            return afterScheme.substringBefore("/").takeIf { it.isNotEmpty() }
        }

    /**
     * URI path segments, decoded.
     *
     * For SAF URIs like `content://authority/tree/primary%3Afolder%2Ffile`,
     * returns: ["tree", "primary", "folder", "file"]
     *
     * The encoded colon (%3A) and slash (%2F) are treated as segment separators.
     */
    val pathSegments: List<String>
        get() {
            val path = path ?: return emptyList()
            if (path.isEmpty() || path == "/") return emptyList()

            // Decode the entire path first
            val decoded = decode(path)

            // Split by both forward slash and colon (SAF uses colon as separator)
            return decoded
                .removePrefix("/")
                .split('/', ':')
                .filter { it.isNotEmpty() }
        }

    /**
     * Raw URI path (everything after authority, before query/fragment)
     */
    val path: String?
        get() {
            if (!rawUri.contains("://")) return null
            val afterScheme = rawUri.substringAfter("://")
            if (!afterScheme.contains("/")) return null

            val pathAndRest = afterScheme.substringAfter("/", "")
            val pathPart = pathAndRest.substringBefore("?").substringBefore("#")
            return if (pathPart.isNotEmpty()) "/$pathPart" else null
        }

    /**
     * Convert to Android Uri for framework API boundaries.
     */
    fun toAndroidUri(): android.net.Uri = android.net.Uri.parse(rawUri)

    override fun toString(): String = rawUri

    companion object {
        /**
         * Parse a URI string into SafUri.
         *
         * @param uriString URI string to parse
         * @return Parsed SafUri
         */
        fun parse(uriString: String): SafUri = SafUri(uriString)

        /**
         * Create SafUri from Android Uri (for API boundaries).
         *
         * @param uri Android Uri to convert
         * @return SafUri with the same URI string
         */
        fun fromAndroidUri(uri: android.net.Uri): SafUri = SafUri(uri.toString())

        /**
         * URL-encode a string for use in URIs.
         *
         * Uses UTF-8 encoding and converts spaces to %20.
         */
        fun encode(value: String): String {
            return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20") // URLEncoder uses + for spaces, but URIs use %20
        }

        /**
         * URL-decode a string from a URI.
         *
         * Uses UTF-8 decoding.
         */
        fun decode(value: String): String {
            return URLDecoder.decode(value, "UTF-8")
        }
    }
}
