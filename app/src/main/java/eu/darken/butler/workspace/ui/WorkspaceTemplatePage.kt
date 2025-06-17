package eu.darken.butler.workspace.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace

@Composable
fun WorkspaceTemplatePage(
    id: Workspace.Id,
    templates: List<WorkspaceTemplate>,
    onTabAction: (TabAction) -> Unit,
    onNavToSettings: () -> Unit,
) {
    val randomSlogan = remember { Slogans.random }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            items(templates.size) { index ->
                val template = templates[index]
                val isFirstItem = index == 0

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onTabAction(
                                TabAction.Create(
                                    type = template.type,
                                    arguments = template.arguments,
                                    replace = id
                                )
                            )
                        },
                    colors = if (isFirstItem) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = template.title.asComposable(),
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (template.description != CaString.EMPTY) {
                                Text(
                                    text = template.description.asComposable(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.mascot),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = randomSlogan.get(LocalContext.current),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = BuildConfigWrap.VERSION_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            IconButton(onClick = onNavToSettings) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = null)
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceTemplatePagePreview() {
    PreviewWrapper {
        val sampleTemplates = listOf(
            WorkspaceTemplate(
                title = R.string.explorer_title.toCaString(),
                description = "Browse files and directories".toCaString(),
                type = Workspace.Type.EXPLORER,
                arguments = null
            ),
            WorkspaceTemplate(
                title = R.string.searcher_title.toCaString(),
                description = "Search for files and content".toCaString(),
                type = Workspace.Type.SEARCH,
                arguments = null
            ),
            WorkspaceTemplate(
                title = R.string.editor_title.toCaString(),
                description = "Edit text files".toCaString(),
                type = Workspace.Type.EDITOR,
                arguments = null
            )
        )

        WorkspaceTemplatePage(
            id = Workspace.Id(),
            templates = sampleTemplates,
            onTabAction = { },
            onNavToSettings = {},
        )
    }
}
