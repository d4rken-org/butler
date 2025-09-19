package eu.darken.butler.workspace.core.preview

data class EditorPreviewData(
    val fileName: String? = null,
    val contentSnippet: String? = """
        fun main() {
            println("Hello World")
            val items = listOf(1, 2, 3)
            items.forEach { item ->
                process(item)
            }
        }
    """.trimIndent(),
) : PreviewData