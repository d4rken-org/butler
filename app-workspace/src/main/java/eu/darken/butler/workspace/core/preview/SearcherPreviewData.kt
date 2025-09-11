package eu.darken.butler.workspace.core.preview

data class SearcherPreviewData(
    val query: String? = "Search files...",
    val results: List<String> = listOf(
        "result_file_1.txt",
        "document_2.pdf",
        "image_3.png"
    ),
    val resultCount: Int = 0,
) : PreviewData