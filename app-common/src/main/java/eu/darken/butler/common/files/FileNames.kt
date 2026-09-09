package eu.darken.butler.common.files

import eu.darken.butler.common.debug.bugreport.takeCodePoints
import java.text.Normalizer

/**
 * Turns [raw] into something a file system reads as a plain name, capped at [maxLength] code points.
 *
 * Unicode letters and digits are kept — an ASCII-only filter would sanitize `厨房` to nothing and
 * `Küche` to `K_che`, which is exactly the recognizability the name exists for. Only what a file
 * system reads as structure is replaced. The result can be empty, e.g. for `/\:*`.
 */
fun sanitizeForFileName(raw: String, maxLength: Int): String = Normalizer
    .normalize(raw, Normalizer.Form.NFC)
    .map { if (it in RESERVED_NAME_CHARS || it.isISOControl()) '_' else it }
    .joinToString("")
    .replace(UNDERSCORE_RUN, "_")
    .trim()
    // A leading dot makes the file hidden. Dots and underscores are dropped together because
    // trimming them in sequence can put one back: "/.Hidden" sanitizes to "_.Hidden", and trimming
    // the underscore afterwards re-exposes the dot.
    .dropWhile { it == '.' || it == '_' }
    .trimEnd('_')
    .takeCodePoints(maxLength)
    .trimEnd('_')

private val RESERVED_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private val UNDERSCORE_RUN = Regex("_+")
