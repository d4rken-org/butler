package eu.darken.butler.common.theming

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.SampleContent

/**
 * Root of a composition: `MaterialTheme` provides no `LocalContentColor`, so without a surface at
 * the top everything falls back to the `Color.Black` sentinel. `contentColor` is passed explicitly
 * instead of letting [Surface] derive it, so the result does not depend on `contentColorFor`
 * matching `background` before `surface`.
 */
@Composable
fun ButlerRootSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        content()
    }
}

@Preview2
@Composable
private fun ButlerRootSurfacePreview() {
    ButlerTheme {
        ButlerRootSurface {
            SampleContent()
        }
    }
}
