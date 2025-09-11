package eu.darken.butler.searcher.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.SearcherPreviewData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.parcelize.Parcelize


class SearcherWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
) : Workspace {

    private val tag = logTag( "Searcher","Workspace", id.shortTag)

    override val type: Workspace.Type = Workspace.Type.SEARCHER

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Searcher ${id.shortTag}".toCaString(),
            previewData = SearcherPreviewData(),
        )
    )

    init {
        log(tag, INFO) { "Initialized" }
    }

    @Parcelize
    data class Arguments(
        val placeholder: String,
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.SEARCHER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): SearcherWorkspace
    }
}