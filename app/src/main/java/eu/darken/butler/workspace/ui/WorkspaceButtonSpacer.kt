package eu.darken.butler.workspace.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WorkspaceButtonSpacer() {
    Spacer(modifier = Modifier.Companion.size(56.dp)) // Match workspace button size
}