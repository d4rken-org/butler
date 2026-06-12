package eu.darken.butler.workspace.ui.template

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

data class QuickCreateItem(
    val type: Workspace.Type,
    val icon: ImageVector,
    val title: CaString,
    val arguments: Workspace.Arguments,
)

fun WorkspaceTemplate.toQuickCreateItem() = QuickCreateItem(
    type = type,
    icon = icon,
    title = title,
    arguments = arguments,
)
