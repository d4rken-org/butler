package eu.darken.butler.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceButtonSpacer


@Composable
fun EditorWorkspacePageHost(
    id: Workspace.Id,
    vm: EditorWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: EditorWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }
    state?.let { state ->
        EditorWorkspacePage(
            state = state,
        )
    }
}

@Composable
fun EditorWorkspacePage(
    state: EditorWorkspaceViewModel.State,
) {
    var editorContent by remember {
        mutableStateOf(
            """
                # Sample Markdown

                This is a **bold** text and this is *italic*.

                ## Code Example

                ```kotlin
                fun hello() {
                    println("Hello, World!")
                }
                ```

                > This is a blockquote
                """.trimIndent()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Editor section with spacer for floating button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "Editor",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            WorkspaceButtonSpacer()
        }

        OutlinedTextField(
            value = editorContent,
            onValueChange = { editorContent = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = { Text("Enter your text here...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            trailingIcon = {
                if (editorContent.isNotEmpty()) {
                    IconButton(onClick = { editorContent = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear"
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // Preview section
        Text(
            text = "Preview",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = editorContent,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Preview2
@Composable
private fun EditorPagePreview() {
    PreviewWrapper {
        EditorWorkspacePage(
            state = EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
            )
        )
    }
}
