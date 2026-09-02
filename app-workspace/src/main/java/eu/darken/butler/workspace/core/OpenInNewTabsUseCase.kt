package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import javax.inject.Inject

/**
 * Use case for opening multiple items (directories and files) as new workspace tabs.
 * Handles the logic for categorizing items and determining what can be opened.
 */
class OpenInNewTabsUseCase @Inject constructor() {
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

        /**
         * [isText] no longer picks the workspace - it says what the item IS, which callers still
         * need for things that are not routing (the drag payload's kind, for one).
         */
        data class File(override val path: APath<*>, val isText: Boolean) : Item
    }

    data class AnalysisResult(
        val directoriesToOpen: List<APath<*>>,
        val viewerFilesToOpen: List<APath<*>>,
        val skippedCount: Int,
        val totalOpenableCount: Int,
        val needsConfirmation: Boolean,
    ) {
        val hasItemsToOpen: Boolean
            get() = totalOpenableCount > 0
    }

    /**
     * The routing rule shared by every "open this" entry point: a directory goes to the Explorer and
     * a file to the Viewer, which renders images, PDFs, packages and text and explains the rest.
     * Single source of truth for both the single-item and the multi-select path.
     *
     * A text file lands here too rather than in the Editor: "open" means look at it, and the Editor
     * is reached by the action that says so. Pinned by [OpenInNewTabsUseCaseTest].
     */
    fun classify(item: Item): Workspace.Type = when (item) {
        is Item.Directory -> Workspace.Type.EXPLORER
        is Item.File -> Workspace.Type.VIEWER
    }

    /**
     * Analyzes the items and determines what can be opened
     */
    fun analyze(request: Request): AnalysisResult {
        log(tag) { "analyze(): ${request.items.size} items from workspace ${request.sourceWorkspaceId.shortTag}" }

        val directories = mutableListOf<APath<*>>()
        val viewerFiles = mutableListOf<APath<*>>()

        request.items.forEach { item ->
            when (classify(item)) {
                Workspace.Type.EXPLORER -> directories.add(item.path)
                else -> viewerFiles.add(item.path)
            }
        }

        val totalOpenableCount = directories.size + viewerFiles.size
        val needsConfirmation = totalOpenableCount >= CONFIRMATION_THRESHOLD

        log(tag) {
            "Analysis: ${directories.size} directories, ${viewerFiles.size} viewer files, " +
                "confirmation: $needsConfirmation"
        }

        return AnalysisResult(
            directoriesToOpen = directories,
            viewerFilesToOpen = viewerFiles,
            skippedCount = 0,
            totalOpenableCount = totalOpenableCount,
            needsConfirmation = needsConfirmation,
        )
    }

    /**
     * Single-item counterpart to [analyze] + [createRequests], used by the "Open" action so it
     * routes through the exact same classification.
     */
    fun createRequest(
        item: Item,
        createExplorerArguments: (APath<*>) -> Workspace.Arguments,
        createViewerArguments: (APath<*>) -> Workspace.Arguments,
    ): WorkspaceAction.Create {
        val type = classify(item)
        log(tag) { "createRequest(): ${item.path} -> $type" }
        return WorkspaceAction.Create(
            type = type,
            arguments = when (type) {
                Workspace.Type.EXPLORER -> createExplorerArguments(item.path)
                else -> createViewerArguments(item.path)
            },
        )
    }

    /**
     * Creates workspace creation requests from the analysis result.
     * The caller must provide functions to create appropriate workspace arguments for each type.
     */
    fun createRequests(
        analysis: AnalysisResult,
        createExplorerArguments: (APath<*>) -> Workspace.Arguments,
        createViewerArguments: (APath<*>) -> Workspace.Arguments,
    ): List<WorkspaceAction.Create> {
        log(tag) {
            "createRequests(): Creating ${analysis.totalOpenableCount} workspace requests " +
                "(${analysis.directoriesToOpen.size} Explorer, ${analysis.viewerFilesToOpen.size} Viewer)"
        }

        return buildList {
            analysis.directoriesToOpen.forEach { path ->
                add(
                    WorkspaceAction.Create(
                        type = Workspace.Type.EXPLORER,
                        arguments = createExplorerArguments(path),
                    )
                )
            }

            analysis.viewerFilesToOpen.forEach { path ->
                add(
                    WorkspaceAction.Create(
                        type = Workspace.Type.VIEWER,
                        arguments = createViewerArguments(path),
                    )
                )
            }
        }
    }
}
