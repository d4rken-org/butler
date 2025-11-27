package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SaverWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    SaverWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
private fun SaverWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<SaverWorkspaceViewModel.State>,
    workspaceButtonStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: SaverWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val state by stateSource.collectAsState(
        initial = SaverWorkspaceViewModel.State()
    )
    val workspaceButtonState by workspaceButtonStateSource.collectAsState(null)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SaverHeader(
                subtitle = state.callerLabel,
                workspaceButtonState = workspaceButtonState,
                workspaceId = workspaceId,
                workspaceActionHandler = workspaceActionHandler,
            )

            FilePreviewCard(sourceInfo = state.sourceInfo)

            SourceFileCard(sourceInfo = state.sourceInfo)

            if (state.sourceInfo?.isAccessible == false) {
                WarningCard(
                    message = stringResource(R.string.saver_source_expired_warning),
                    onRetry = { vm?.onRefreshAccessibility() },
                    onClose = { vm?.onClose() },
                )
            } else {
                DestinationCard(
                    destination = state.destination,
                    filename = state.filename,
                    onClick = { vm?.onPickDestination() },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            SaverActionArea(
                state = state,
                onSave = { vm?.onSave() },
                onOpenSaved = { vm?.onOpenSavedFile() },
            )
        }
    }
}

@Preview2
@Composable
private fun SaverWorkspacePagePreview() {
    PreviewWrapper {
        SaverWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(
                SaverWorkspaceViewModel.State(
                    filename = "image.jpg",
                    callerLabel = "Telegram",
                )
            ),
            workspaceButtonStateSource = flowOf(null),
        )
    }
}
