package eu.darken.butler.common.debug.logging

fun logTag(vararg tags: String): String {
    val sb = StringBuilder("BTLR:")
    for (i in tags.indices) {
        sb.append(tags[i])
        if (i < tags.size - 1) sb.append(":")
    }
    return sb.toString()
}