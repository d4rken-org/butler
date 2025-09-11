package eu.darken.butler.workspace.ui.manager.rows.preview

sealed interface PreviewData

data class ExplorerPreviewData(
    val currentPath: String? = null,
    val items: List<ExplorerPreviewItem> = listOf(
        ExplorerPreviewItem("Documents", true),
        ExplorerPreviewItem("Downloads", true),
        ExplorerPreviewItem("Pictures", true),
        ExplorerPreviewItem("config.xml", false),
    ),
) : PreviewData

data class ExplorerPreviewItem(
    val name: String,
    val isDirectory: Boolean,
)

data class SearcherPreviewData(
    val query: String? = "Search files...",
    val results: List<String> = listOf(
        "result_file_1.txt",
        "document_2.pdf",
        "image_3.png"
    ),
    val resultCount: Int = 0,
) : PreviewData

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

data class TemplatesPreviewData(
    val templateCount: Int = 0,
) : PreviewData