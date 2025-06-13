package eu.darken.butler.workspace.ui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.SampleContent

@Composable
internal fun HomeTabContent() {
    LazyColumn(
        modifier = Modifier.Companion.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Card {
                Column(modifier = Modifier.Companion.padding(16.dp)) {
                    Text(text = "Quick Actions", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.Companion.width(8.dp))
                    Text("Browse files, search, or create new documents")
                }
            }
        }
        items(3) { index -> SampleContent {} }
    }
}