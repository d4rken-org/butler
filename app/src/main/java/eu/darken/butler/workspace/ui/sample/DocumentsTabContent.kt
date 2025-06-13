package eu.darken.butler.workspace.ui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentsTabContent() {
    LazyColumn(
        modifier = Modifier.Companion.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Documents",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item { Text("Your document files will appear here") }
        items(5) { index ->
            Card {
                Row(
                    modifier = Modifier.Companion.padding(16.dp),
                    verticalAlignment = Alignment.Companion.CenterVertically
                ) { Text("Document ${index + 1}.pdf") }
            }
        }
    }
}