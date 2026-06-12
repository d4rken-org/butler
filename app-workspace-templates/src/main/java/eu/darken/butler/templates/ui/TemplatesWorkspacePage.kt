package eu.darken.butler.templates.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.navigation.settings
import androidx.compose.runtime.collectAsState
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.templates.R
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

@Composable
fun TemplatesWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: TemplatesWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: TemplatesWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { state ->
        TemplatesWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            onCreateWorkspace = { vm.createWorkspace(it) },
        )
    }
}

@Composable
fun TemplatesWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: TemplatesWorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onCreateWorkspace: (WorkspaceAction.Create) -> Unit = {},
) {
    val randomSlogan = remember { Slogans.random }

    // System bar insets for edge-to-edge (based on pane edges)
    val density = LocalDensity.current
    val statusBarInset = if (design.paneEdges.touchesTop) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else 0.dp
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    // Dynamically measured settings card height for content padding
    val localDensity = LocalDensity.current
    var settingsCardHeight by remember { mutableStateOf(96.dp) } // Initial estimate

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrollable content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
            contentPadding = PaddingValues(
                top = statusBarInset + 16.dp,
                bottom = settingsCardHeight + 16.dp,
            ),
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
            items(state.templates.size) { index ->
                val template = state.templates[index]
                val isFirstItem = index == 0

                TemplateCard(
                    template = template,
                    isFirstItem = isFirstItem,
                    onClick = {
                        onCreateWorkspace(
                            WorkspaceAction.Create(
                                type = template.type,
                                arguments = template.arguments,
                                replace = state.id,
                                autoFocus = true,
                            )
                        )
                    },
                )
            }
        }

        // Floating settings card with gradient fades
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    settingsCardHeight = with(localDensity) { coordinates.size.height.toDp() }
                },
        ) {
            // Gradient fades behind card's rounded corners
            GradientFade(
                modifier = Modifier.align(Alignment.TopCenter),
                fadeDirection = FadeDirection.DOWN,
            )
            GradientFade(
                modifier = Modifier.align(Alignment.BottomCenter),
                height = 48.dp + navBarInset,
                fadeDirection = FadeDirection.UP,
            )

            // Floating settings card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = navBarInset + 16.dp)
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onNavToSettings() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ButlerMascot(
                        modifier = Modifier.size(64.dp),
                        variant = if (state.isUpgraded) ButlerMascotMode.Static.Happy() else ButlerMascotMode.Static.Normal()
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        if (state.isUpgraded) {
                            ColoredTitleText(
                                fullTitle = stringResource(eu.darken.butler.common.R.string.app_name_upgraded),
                                postfix = stringResource(eu.darken.butler.common.R.string.app_name_upgrade_postfix),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            Text(
                                text = stringResource(eu.darken.butler.common.R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = randomSlogan.get(LocalContext.current),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.versionDescription,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 2.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }

                    Icon(
                        imageVector = Icons.TwoTone.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        if (design.isSingle) {
            WorkspaceButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusBarInset + 16.dp, end = 16.dp),
                buttonSize = 48.dp,
                currentWorkspaceId = workspaceId,
            )
        }
    }
}

@Composable
private fun TemplateCard(
    template: WorkspaceTemplate,
    isFirstItem: Boolean,
    onClick: () -> Unit
) {
    val cardContent = @Composable {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isFirstItem) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = template.icon,
                    contentDescription = null,
                    tint = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = template.title.asComposable(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = template.subtitle.asComposable(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Icon(
                imageVector = Icons.TwoTone.Add,
                contentDescription = null,
                tint = if (isFirstItem) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (isFirstItem) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) { cardContent() }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.cardColors()
        ) { cardContent() }
    }
}

private enum class FadeDirection { UP, DOWN }

@Composable
private fun GradientFade(
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    fadeDirection: FadeDirection = FadeDirection.DOWN,
) {
    val colors = when (fadeDirection) {
        FadeDirection.DOWN -> listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
        FadeDirection.UP -> listOf(MaterialTheme.colorScheme.surface, Color.Transparent)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(colors = colors))
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TemplatesWorkspacePagePreview() {
    val workspaceId = Workspace.Id()
    TemplatesWorkspacePage(
        workspaceId = workspaceId,
        state = TemplatesWorkspaceViewModel.State(
            id = workspaceId,
            templates = listOf(
                previewTemplate(Workspace.Type.EXPLORER, "Explorer", "Browse and manage files", 10),
                previewTemplate(Workspace.Type.SEARCHER, "Searcher", "Find files and folders", 20),
                previewTemplate(Workspace.Type.EDITOR, "Editor", "View and edit text files", 30),
                previewTemplate(Workspace.Type.APPS, "Apps", "Manage installed apps", 40),
            ),
            isUpgraded = true,
            versionDescription = "1.0.0-preview",
        ),
        onNavToSettings = {},
    )
}

private fun previewTemplate(
    type: Workspace.Type,
    title: String,
    subtitle: String,
    order: Int,
) = object : WorkspaceTemplate {
    override val type: Workspace.Type = type
    override val icon = type.icon
    override val title: CaString = title.toCaString()
    override val subtitle: CaString = subtitle.toCaString()
    override val arguments: Workspace.Arguments = TemplatesArguments.Default()
    override val sortOrder: Int = order
}
