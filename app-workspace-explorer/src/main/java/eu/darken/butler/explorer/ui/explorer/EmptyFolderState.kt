package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import eu.darken.butler.explorer.R
import kotlin.random.Random

@Composable
fun EmptyFolderState(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.TwoTone.FolderOpen,
    title: String? = null,
    caption: String? = null
) {
    val defaultTitle = stringResource(R.string.explorer_empty_folder_title)
    val randomCaption = remember {
        caption ?: funnyEmptyFolderCaptions.random()
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // Decorative
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(64.dp)
            )
            
            Text(
                text = title ?: defaultTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            Text(
                text = randomCaption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private val funnyEmptyFolderCaptions = listOf(
    "Nothing to see here... move along 🕵️",
    "This folder is on a diet",
    "Even the files went home early today",
    "Folder.exe has stopped working",
    "The files are playing hide and seek",
    "404: Files not found",
    "This folder has trust issues",
    "Files went on vacation without notice",
    "Empty like my soul... but more organized",
    "The digital equivalent of tumbleweeds",
    "Files are socially distancing",
    "This folder practices minimalism",
    "Someone forgot to feed the files",
    "The files have left the building",
    "Emptier than a promise from a politician"
)

@Preview(showBackground = true)
@Composable
fun EmptyFolderStatePreview() {
    MaterialTheme {
        EmptyFolderState()
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyFolderStateCustomPreview() {
    MaterialTheme {
        EmptyFolderState(
            title = stringResource(R.string.explorer_empty_search_title),
            caption = stringResource(R.string.explorer_empty_search_caption)
        )
    }
}