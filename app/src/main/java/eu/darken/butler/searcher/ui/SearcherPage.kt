package eu.darken.butler.searcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace

sealed class SearchResult(
    open val name: String,
    open val path: String,
) {
    data class Folder(
        override val name: String,
        override val path: String,
    ) : SearchResult(name, path)

    data class File(
        override val name: String,
        override val path: String,
        val size: String,
        val lastModified: String
    ) : SearchResult(name, path)
}

@Composable
fun SearcherPage(
    id: Workspace.Id,
) {
    var searchQuery by remember { mutableStateOf("") }

    val mockResults = remember {
        listOf(
            SearchResult.File(
                name = "Project Plan.docx",
                path = "/Documents/Project Plan.docx",
                size = "458 KB",
                lastModified = "2023-10-20"
            ),
            SearchResult.Folder(name = "Projects", path = "/Documents/Projects"),
            SearchResult.File(
                name = "project_notes.txt",
                path = "/Documents/Projects/project_notes.txt",
                size = "15 KB",
                lastModified = "2023-10-18"
            ),
            SearchResult.File(
                name = "Vacation.jpg",
                path = "/Pictures/Vacation.jpg",
                size = "3.5 MB",
                lastModified = "2023-09-05"
            ),
            SearchResult.File(
                name = "Budget.xlsx",
                path = "/Budget.xlsx",
                size = "345 KB",
                lastModified = "2023-10-18"
            ),
            SearchResult.Folder(name = "Downloads", path = "/Downloads"),
            SearchResult.File(
                name = "document.pdf",
                path = "/Downloads/document.pdf",
                size = "2.1 MB",
                lastModified = "2023-10-22"
            )
        )
    }

    val filteredResults =
        remember(searchQuery, mockResults) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                mockResults.filter { result ->
                    result.name.contains(searchQuery, ignoreCase = true) ||
                        result.path.contains(searchQuery, ignoreCase = true)
                }
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.padding(16.dp)
        )

        when {
            searchQuery.isBlank() -> {
                Text(
                    text = "Search files and folders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }

            filteredResults.isEmpty() -> {
                Text(
                    text = "No results found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }

            else -> {
                Text(
                    text = "${filteredResults.size} results",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(filteredResults) { result ->
                        SearchResultRow(result = result, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(text = "Search files and folders") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (result) {
                is SearchResult.Folder -> {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                is SearchResult.File -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.name, style = MaterialTheme.typography.bodyLarge)

                Text(
                    text = result.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (result is SearchResult.File) {
                    Text(
                        text = "${result.size} • ${result.lastModified}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SearchPagePreview() {
    PreviewWrapper { SearcherPage(id = Workspace.Id()) }
}

@Preview2
@Composable
private fun SearchBarPreview() {
    PreviewWrapper {
        Column(modifier = Modifier.padding(16.dp)) {
            SearchBar(query = "", onQueryChange = {})

            Spacer(modifier = Modifier.height(16.dp))

            SearchBar(query = "project", onQueryChange = {})
        }
    }
}

@Preview2
@Composable
private fun SearchResultRowPreview() {
    PreviewWrapper {
        Column(modifier = Modifier.padding(16.dp)) {
            SearchResultRow(
                result = SearchResult.Folder(name = "Projects", path = "/Documents/Projects"),
                onClick = {}
            )

            Spacer(modifier = Modifier.height(16.dp))

            SearchResultRow(
                result =
                    SearchResult.File(
                        name = "Project Plan.docx",
                        path = "/Documents/Project Plan.docx",
                        size = "458 KB",
                        lastModified = "2023-10-20"
                    ),
                onClick = {}
            )
        }
    }
}
