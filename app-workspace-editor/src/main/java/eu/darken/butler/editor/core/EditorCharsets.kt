package eu.darken.butler.editor.core

import java.nio.charset.Charset

/**
 * Charsets the editor can be asked to reopen a document with. Only the UTF family and
 * single-byte charsets are safe with the block-scan boundary snapping (no partial multi-byte
 * sequences at block edges); DBCS encodings (Shift-JIS, GBK, Big5) would need DBCS-aware
 * snapping and are deliberately excluded.
 */
object EditorCharsets {

    val allowlist: List<Charset> = listOf(
        Charsets.UTF_8,
        Charsets.UTF_16LE,
        Charsets.UTF_16BE,
        Charsets.ISO_8859_1,
        Charset.forName("windows-1252"),
        Charsets.US_ASCII,
    )

    /** Resolves a persisted charset name against the allowlist (alias-aware); null if unknown. */
    fun resolve(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return null
        return allowlist.firstOrNull { it == charset }
    }
}
