package eu.darken.butler.workspace.ui.template

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface WorkspaceTemplate {
    val type: Workspace.Type
    val icon: ImageVector
    val title: CaString
    val subtitle: CaString
    val arguments: Workspace.Arguments
    val sortOrder: Int
    val isQuickCreate: Boolean get() = false
    val availability: Flow<Boolean> get() = flowOf(true)
    val accent: Accent get() = Accent.DEFAULT

    enum class Accent { DEFAULT, TERTIARY }
}
