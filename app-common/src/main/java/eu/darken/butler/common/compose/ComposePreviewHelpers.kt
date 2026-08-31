package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.compose.tour.NoOpGuidedTourAccess
import eu.darken.butler.common.theming.ButlerRootSurface
import eu.darken.butler.common.theming.ButlerTheme
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle

@Composable
fun SampleContent(
    text: String = "Sample text",
    action: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = action) { Text("Click Me") }
        }
    }
}

@Preview2
@Composable
fun SampleContentPreview() {
    PreviewWrapper {
        SampleContent(text = "Sample Text")
    }
}

@Composable
fun PreviewWrapper(
    theme: ThemeState = ThemeState(ThemeMode.SYSTEM, style = ThemeStyle.DEFAULT),
    content: @Composable () -> Unit
) {
    ButlerTheme(
        state = theme,
    ) {
        ButlerRootSurface {
            // LocalGuidedTourController has no default (a missing provider must fail loudly in the
            // real app), so anything previewed/tested outside MainActivity needs the no-op stand-in.
            CompositionLocalProvider(LocalGuidedTourController provides NoOpGuidedTourAccess) {
                content()
            }
        }
    }
}

class ButlerPreviewWrapper : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        PreviewWrapper { content() }
    }
}
