package eu.darken.butler.workspace.ui.error

import android.os.Build
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.asLog

object ErrorFormatter {

    fun formatErrorForClipboard(
        throwable: Throwable,
        context: String? = null,
    ): String {
        return buildString {
            appendLine("# Error Report")
            appendLine("* `${Build.FINGERPRINT}`")
            appendLine("* `${BuildConfigWrap.VERSION_DESCRIPTION}`")

            context?.let {
                appendLine("* Context: $it")
            }

            appendLine()
            appendLine("## Error")
            appendLine(throwable.message ?: throwable.javaClass.simpleName)
            appendLine()
            appendLine("```java")
            appendLine(throwable.asLog())
            appendLine("```")
        }.trimEnd()
    }
}
