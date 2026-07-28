package eu.darken.butler.templates.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.navigation.settings
import androidx.compose.runtime.collectAsState
import eu.darken.butler.templates.R
import eu.darken.butler.templates.ui.preview.TemplatesMockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

object TemplatesWorkspacePageDefaults {
    const val SETTINGS_CARD_TEST_TAG = "templates.settingsCard"
}

@Composable
fun TemplatesWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: TemplatesWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: TemplatesWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { state ->
        TemplatesWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            onCreateWorkspace = { vm.createWorkspace(it) },
            onRename = { vm.renameWorkspace(it) },
            onEditName = { vm.showRenameDialog() },
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
    onRename: (String?) -> Unit = {},
    onEditName: () -> Unit = {},
) {
    // Previews and screenshot renders must be reproducible, a random slogan is not.
    val isInspection = LocalInspectionMode.current
    val slogan = remember(isInspection) { if (isInspection) Slogans.fixed else Slogans.random }

    val density = LocalDensity.current
    val paneInsets = design.paneInsets()
    val statusBarInset = paneInsets.top
    val navBarInset = paneInsets.bottom

    // Dynamically measured settings card height for content padding
    var settingsCardHeight by remember { mutableStateOf(96.dp) } // Initial estimate

    val listState = rememberWorkspaceLazyListState(workspaceId, slot = TemplatesScrollSlots.LIST)

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrollable content
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
            contentPadding = PaddingValues(
                top = statusBarInset + 16.dp,
                // The floating settings card only exists in single-pane; without it we just
                // reserve the nav bar inset instead of the (stale) measured card height.
                bottom = if (design.isSingle) settingsCardHeight + 16.dp else navBarInset + 16.dp,
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
            item {
                TabNameRow(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    customTitle = state.customTitle,
                    onEdit = onEditName,
                    onClear = { onRename(null) },
                )
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

        // Floating settings card with gradient fades.
        // Single-pane only: every multi-pane layout renders the navigation rail, which
        // already exposes a Butler/settings button, so this card would be redundant there.
        if (design.isSingle) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        settingsCardHeight = with(density) { coordinates.size.height.toDp() }
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
                            .testTag(TemplatesWorkspacePageDefaults.SETTINGS_CARD_TEST_TAG)
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
                                text = slogan.get(LocalContext.current),
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

    // The rename dialog lives in the page host's overlay slot, see TemplatesWorkspaceOverlays
}

/**
 * Lets the user name the tab before it becomes something: the name survives the template morph,
 * so it carries onto the resulting Explorer/Searcher/… tab.
 */
@Composable
private fun TabNameRow(
    modifier: Modifier = Modifier,
    customTitle: String?,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    if (customTitle == null) {
        TextButton(
            modifier = modifier,
            onClick = onEdit,
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                imageVector = Icons.TwoTone.Edit,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(R.string.workspace_templates_name_tab_action),
            )
        }
    } else {
        AssistChip(
            modifier = modifier,
            onClick = onEdit,
            label = {
                Text(
                    text = customTitle,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            },
            leadingIcon = {
                Icon(
                    modifier = Modifier.size(18.dp),
                    imageVector = Icons.TwoTone.Edit,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                IconButton(
                    modifier = Modifier.size(24.dp),
                    onClick = onClear,
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(R.string.workspace_templates_name_tab_clear_content_desc),
                    )
                }
            },
        )
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
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) { cardContent() }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth(),
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
        state = TemplatesMockDataProvider.createMockState(workspaceId),
        onNavToSettings = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TemplatesWorkspacePageNamedPreview() {
    val workspaceId = Workspace.Id()
    TemplatesWorkspacePage(
        workspaceId = workspaceId,
        state = TemplatesMockDataProvider.createMockState(workspaceId, customTitle = "Holiday photos"),
        onNavToSettings = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TemplatesWorkspacePageMultiPanePreview() {
    val workspaceId = Workspace.Id()
    TemplatesWorkspacePage(
        workspaceId = workspaceId,
        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
        state = TemplatesMockDataProvider.createMockState(workspaceId),
        onNavToSettings = {},
    )
}
