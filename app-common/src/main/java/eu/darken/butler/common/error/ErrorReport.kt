package eu.darken.butler.common.error

data class ErrorReport(
    val title: String,
    val deviceFingerprint: String,
    val appVersion: String,
    val customMessage: String?,
    val context: String?,
    val errorMessage: String,
    val stackTrace: String,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("## Device Info")
        appendLine("* `$deviceFingerprint`")
        appendLine("* `$appVersion`")
        context?.let {
            appendLine("* Context: $it")
        }
        customMessage?.let {
            appendLine()
            appendLine("## Message")
            appendLine(it)
        }
        appendLine()
        appendLine("## Error")
        appendLine(errorMessage)
        appendLine()
        appendLine("```java")
        appendLine(stackTrace)
        appendLine("```")
    }.trimEnd()

    fun toPlainText(): String = buildString {
        appendLine(title)
        appendLine("Device: $deviceFingerprint")
        appendLine("Version: $appVersion")
        context?.let { appendLine("Context: $it") }
        customMessage?.let { appendLine("Message: $it") }
        appendLine()
        appendLine("Error: $errorMessage")
        appendLine()
        appendLine(stackTrace)
    }.trimEnd()
}
