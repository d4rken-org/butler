package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OpenInNewTabsUseCaseTest : BaseTest() {

    private val useCase = OpenInNewTabsUseCase()
    private val workspaceId = Workspace.Id()

    private val directory = LocalPath.build("/storage/emulated/0/DCIM")
    private val textFile = LocalPath.build("/storage/emulated/0/Documents/notes.txt")
    private val imageFile = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val binaryFile = LocalPath.build("/storage/emulated/0/Download/blob.bin")

    private fun analyze(vararg items: OpenInNewTabsUseCase.Item) = useCase.analyze(
        OpenInNewTabsUseCase.Request(items = items.toList(), sourceWorkspaceId = workspaceId),
    )

    private fun requests(analysis: OpenInNewTabsUseCase.AnalysisResult) = useCase.createRequests(
        analysis = analysis,
        createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
        createEditorArguments = { EditorArguments.Default(filePath = it) },
        createViewerArguments = { ViewerArguments.Default(filePath = it) },
    )

    @Test
    fun `directories route to the explorer`() {
        val analysis = analyze(OpenInNewTabsUseCase.Item.Directory(directory))

        analysis.directoriesToOpen shouldContainExactly listOf<APath<*>>(directory)
        analysis.textFilesToOpen.isEmpty() shouldBe true
        analysis.viewerFilesToOpen.isEmpty() shouldBe true

        val request = requests(analysis).single()
        request.type shouldBe Workspace.Type.EXPLORER
        request.arguments shouldBe ExplorerArguments.Default(startPath = directory)
    }

    @Test
    fun `text files route to the editor`() {
        val analysis = analyze(OpenInNewTabsUseCase.Item.File(textFile, isText = true))

        analysis.textFilesToOpen shouldContainExactly listOf<APath<*>>(textFile)
        analysis.viewerFilesToOpen.isEmpty() shouldBe true

        val request = requests(analysis).single()
        request.type shouldBe Workspace.Type.EDITOR
        request.arguments shouldBe EditorArguments.Default(filePath = textFile)
    }

    @Test
    fun `images route to the viewer`() {
        val analysis = analyze(OpenInNewTabsUseCase.Item.File(imageFile, isText = false))

        analysis.viewerFilesToOpen shouldContainExactly listOf<APath<*>>(imageFile)

        val request = requests(analysis).single()
        request.type shouldBe Workspace.Type.VIEWER
        request.arguments shouldBe ViewerArguments.Default(filePath = imageFile)
    }

    @Test
    fun `unknown binaries route to the viewer instead of being skipped`() {
        val analysis = analyze(OpenInNewTabsUseCase.Item.File(binaryFile, isText = false))

        analysis.viewerFilesToOpen shouldContainExactly listOf<APath<*>>(binaryFile)
        analysis.skippedCount shouldBe 0
        analysis.totalOpenableCount shouldBe 1
        analysis.hasItemsToOpen shouldBe true
    }

    private fun request(item: OpenInNewTabsUseCase.Item) = useCase.createRequest(
        item = item,
        createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
        createEditorArguments = { EditorArguments.Default(filePath = it) },
        createViewerArguments = { ViewerArguments.Default(filePath = it) },
    )

    @Test
    fun `a single text file opens in the editor`() {
        val request = request(OpenInNewTabsUseCase.Item.File(textFile, isText = true))

        request.type shouldBe Workspace.Type.EDITOR
        request.arguments shouldBe EditorArguments.Default(filePath = textFile)
    }

    @Test
    fun `a single image opens in the viewer`() {
        val request = request(OpenInNewTabsUseCase.Item.File(imageFile, isText = false))

        request.type shouldBe Workspace.Type.VIEWER
        request.arguments shouldBe ViewerArguments.Default(filePath = imageFile)
    }

    @Test
    fun `a single unknown binary opens in the viewer`() {
        val request = request(OpenInNewTabsUseCase.Item.File(binaryFile, isText = false))

        request.type shouldBe Workspace.Type.VIEWER
        request.arguments shouldBe ViewerArguments.Default(filePath = binaryFile)
    }

    @Test
    fun `a single directory opens in the explorer`() {
        val request = request(OpenInNewTabsUseCase.Item.Directory(directory))

        request.type shouldBe Workspace.Type.EXPLORER
        request.arguments shouldBe ExplorerArguments.Default(startPath = directory)
    }

    @Test
    fun `single and multi select agree on the target workspace type`() {
        val items = listOf(
            OpenInNewTabsUseCase.Item.Directory(directory),
            OpenInNewTabsUseCase.Item.File(textFile, isText = true),
            OpenInNewTabsUseCase.Item.File(imageFile, isText = false),
            OpenInNewTabsUseCase.Item.File(binaryFile, isText = false),
        )

        items.forEach { item ->
            request(item).type shouldBe requests(analyze(item)).single().type
        }
    }

    /**
     * The plain "Open" row routes by the same predicate, so a yaml file must not land in the viewer.
     * The Explorer feeds the [MimeInfo] overload, the Searcher the name one - both have to answer
     * the same here.
     */
    @Test
    fun `a yaml file routes to the editor`() {
        val yamlFile = LocalPath.build("/storage/emulated/0/Documents/notes.yaml")

        listOf(
            TextFileDetector.isTextFile(MimeInfo.fromFileName("notes.yaml")),
            TextFileDetector.isTextFile("notes.yaml"),
        ).forEach { isText ->
            useCase.classify(
                OpenInNewTabsUseCase.Item.File(yamlFile, isText = isText),
            ) shouldBe Workspace.Type.EDITOR
        }
    }

    @Test
    fun `a mixed selection produces one request per item, grouped by type`() {
        val analysis = analyze(
            OpenInNewTabsUseCase.Item.Directory(directory),
            OpenInNewTabsUseCase.Item.File(textFile, isText = true),
            OpenInNewTabsUseCase.Item.File(imageFile, isText = false),
            OpenInNewTabsUseCase.Item.File(binaryFile, isText = false),
        )

        analysis.totalOpenableCount shouldBe 4
        analysis.skippedCount shouldBe 0

        requests(analysis).map { it.type } shouldContainExactly listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.EDITOR,
            Workspace.Type.VIEWER,
            Workspace.Type.VIEWER,
        )
    }
}
