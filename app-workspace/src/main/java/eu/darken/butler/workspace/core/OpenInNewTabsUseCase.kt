package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import javax.inject.Inject

/**
 * Use case for opening multiple items (directories and files) as new workspace tabs.
 * Handles the logic for categorizing items and determining what can be opened.
 */
class OpenInNewTabsUseCase @Inject constructor(
    private val workspaceRemote: WorkspaceRemote,
) {
    private val tag = logTag("Workspace", "OpenInNewTabsUseCase")

    companion object {
        private const val CONFIRMATION_THRESHOLD = 5
    }

    data class Request(
        val items: List<Item>,
        val sourceWorkspaceId: Workspace.Id,
    )

    sealed interface Item {
        val path: APath<*>

        data class Directory(override val path: APath<*>) : Item
        data class File(override val path: APath<*>, val isText: Boolean) : Item
    }

    data class AnalysisResult(
        val directoriesToOpen: List<APath<*>>,
        val textFilesToOpen: List<APath<*>>,
        val skippedCount: Int,
        val totalOpenableCount: Int,
        val needsConfirmation: Boolean,
    ) {
        val hasItemsToOpen: Boolean
            get() = totalOpenableCount > 0
    }

    /**
     * Analyzes the items and determines what can be opened
     */
    fun analyze(request: Request): AnalysisResult {
        log(tag) { "analyze(): ${request.items.size} items from workspace ${request.sourceWorkspaceId.shortTag}" }

        val directories = mutableListOf<APath<*>>()
        val textFiles = mutableListOf<APath<*>>()
        var skippedCount = 0

        request.items.forEach { item ->
            when (item) {
                is Item.Directory -> {
                    directories.add(item.path)
                }
                is Item.File -> {
                    if (item.isText) {
                        textFiles.add(item.path)
                    } else {
                        skippedCount++
                    }
                }
            }
        }

        val totalOpenableCount = directories.size + textFiles.size
        val needsConfirmation = totalOpenableCount >= CONFIRMATION_THRESHOLD

        log(tag) {
            "Analysis: ${directories.size} directories, ${textFiles.size} text files, " +
                "$skippedCount skipped, confirmation: $needsConfirmation"
        }

        return AnalysisResult(
            directoriesToOpen = directories,
            textFilesToOpen = textFiles,
            skippedCount = skippedCount,
            totalOpenableCount = totalOpenableCount,
            needsConfirmation = needsConfirmation,
        )
    }

    /**
     * Creates workspaces for the analyzed items
     * The caller must provide functions to create appropriate workspace arguments
     */
    suspend fun execute(
        analysis: AnalysisResult,
        createExplorerArguments: (APath<*>) -> Workspace.Arguments,
        createEditorArguments: (APath<*>) -> Workspace.Arguments,
    ) {
        log(tag, INFO) {
            "execute(): Opening ${analysis.totalOpenableCount} workspaces " +
                "(${analysis.directoriesToOpen.size} Explorer, ${analysis.textFilesToOpen.size} Editor)"
        }

        try {
            // Create Explorer workspaces for directories
            analysis.directoriesToOpen.forEach { path ->
                val arguments = createExplorerArguments(path)
                workspaceRemote.execute(
                    WorkspaceAction.Create(
                        type = Workspace.Type.EXPLORER,
                        arguments = arguments,
                    )
                )
                log(tag) { "Created Explorer workspace for: ${path.path}" }
            }

            // Create Editor workspaces for text files
            analysis.textFilesToOpen.forEach { path ->
                val arguments = createEditorArguments(path)
                workspaceRemote.execute(
                    WorkspaceAction.Create(
                        type = Workspace.Type.EDITOR,
                        arguments = arguments,
                    )
                )
                log(tag) { "Created Editor workspace for: ${path.path}" }
            }

            log(tag, INFO) { "Successfully opened ${analysis.totalOpenableCount} workspaces" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create workspaces: ${e.asLog()}" }
            throw e
        }
    }
}
