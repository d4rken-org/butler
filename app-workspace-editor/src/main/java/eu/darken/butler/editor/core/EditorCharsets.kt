package eu.darken.butler.editor.core

import eu.darken.butler.common.files.text.TextCharsets
import java.nio.charset.Charset

/**
 * Charsets the editor can be asked to reopen a document with. Each one needs block-edge snapping
 * in the block scan and a byte mapping for edits, so a charset is only added here together with
 * both.
 */
object EditorCharsets {

    val allowlist: List<Charset> = TextCharsets.selectable

    /** Resolves a persisted charset name against the allowlist (alias-aware); null if unknown. */
    fun resolve(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return null
        return allowlist.firstOrNull { it == charset }
    }
}
