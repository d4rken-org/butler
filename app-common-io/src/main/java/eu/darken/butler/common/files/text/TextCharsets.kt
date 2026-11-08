package eu.darken.butler.common.files.text

import java.nio.charset.Charset

object TextCharsets {

    val GB18030: Charset = Charset.forName("GB18030")

    val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /** The charsets a user can pick by hand, in display order. */
    val selectable: List<Charset> = listOf(
        Charsets.UTF_8,
        GB18030,
        Charsets.UTF_16LE,
        Charsets.UTF_16BE,
        Charsets.ISO_8859_1,
        WINDOWS_1252,
        Charsets.US_ASCII,
    )
}
