package eu.darken.butler.workspace.core.preview

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