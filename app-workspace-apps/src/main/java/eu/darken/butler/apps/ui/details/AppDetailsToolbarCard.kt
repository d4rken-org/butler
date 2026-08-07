package eu.darken.butler.apps.ui.details

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.elements.AppsSearchBar
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun AppDetailsToolbarCard(
    modifier: Modifier = Modifier,
    app: AppInfo?,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    backContentDescription: String? = null,
    currentWorkspaceId: Workspace.Id? = null,
    searchActive: Boolean = false,
    searchQuery: TextFieldValue = TextFieldValue(),
    searchHint: String? = null,
    onSearchQueryChange: (TextFieldValue) -> Unit = {},
    onSearchToggle: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isCollapsed = collapsedFraction > 0.5f

    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 6.dp else 8.dp,
        label = "cardPadding"
    )

    val iconSize by animateDpAsState(
        targetValue = if (isCollapsed) 36.dp else 40.dp,
        label = "iconSize"
    )

    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        // Workspace button: single-pane only, the navigation rail carries it otherwise. Stacked
        // overlays get it too - the tab underneath stays swipeable and the manager renders the
        // overlay as the face of its owning tab's card, so the switcher is neither unreachable nor
        // meaningless there.
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    buttonSize = 40.dp,
                    currentWorkspaceId = currentWorkspaceId,
                )
            }
        } else null,
        cutoutMode = CutoutMode.FullHeight,
        cutoutAlignment = Alignment.CenterVertically,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button (modal close or sub-screen navigation)
            if (onBackClick != null) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                        contentDescription = backContentDescription
                            ?: stringResource(R.string.appdetails_back_generic_action),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                // Add padding in pane mode
                Spacer(modifier = Modifier.width(8.dp))
            }

            // App icon
            if (app != null) {
                TintedAsyncImage(
                    model = app.install,
                    contentDescription = app.label.asComposable(),
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.Android,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // App name plus optional sub-screen subtitle, replaced by the search input while active.
            // Collapsing only affects padding, icon size and title lines — it must never drop the
            // search field, because CollapseOnScroll never hides the bar itself.
            if (searchActive) {
                AppsSearchBar(
                    modifier = Modifier.weight(1f),
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    hint = searchHint ?: stringResource(R.string.apps_components_search_hint),
                    autoFocus = true,
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = app?.label?.get(context) ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (isCollapsed) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (onSearchToggle != null) {
                IconButton(
                    onClick = onSearchToggle,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (searchActive) Icons.TwoTone.Close else Icons.TwoTone.Search,
                        contentDescription = if (searchActive) {
                            stringResource(eu.darken.butler.common.R.string.general_close_action)
                        } else {
                            stringResource(R.string.apps_components_search_action)
                        },
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardExpandedPreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.chrome,
        design = WorkspaceDesign(),
        collapsedFraction = 0f,
        modifier = Modifier.padding(16.dp)
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardCollapsedPreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.largeApp,
        design = WorkspaceDesign(),
        collapsedFraction = 1f,
        modifier = Modifier.padding(16.dp)
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardSubtitlePreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.chrome,
        design = WorkspaceDesign(),
        collapsedFraction = 0f,
        subtitle = stringResource(R.string.apps_details_section_components),
        onBackClick = {},
        onSearchToggle = {},
        modifier = Modifier.padding(16.dp)
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardSearchActivePreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.chrome,
        design = WorkspaceDesign(),
        collapsedFraction = 0f,
        subtitle = stringResource(R.string.apps_details_section_components),
        onBackClick = {},
        searchActive = true,
        searchQuery = TextFieldValue("Main"),
        onSearchToggle = {},
        modifier = Modifier.padding(16.dp)
    )
}

/** Stacked on the workspace that opened it: back button closes the overlay, button still shown. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardStackedPreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.disabledApp,
        design = WorkspaceDesign(),
        collapsedFraction = 0f,
        onBackClick = {},
        modifier = Modifier.padding(16.dp)
    )
}

/** Multi-pane: no workspace button, the navigation rail carries it. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsToolbarCardMultiPanePreview() {
    AppDetailsToolbarCard(
        app = AppsMockDataProvider.Presets.chrome,
        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
        collapsedFraction = 0f,
        modifier = Modifier.padding(16.dp)
    )
}
