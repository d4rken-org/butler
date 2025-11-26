package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R

@Composable
fun ButlerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.butler_mascot_description),
) {
    Image(
        painter = painterResource(id = R.drawable.mascot_normal),
        contentDescription = contentDescription,
        modifier = modifier
    )
}

@Preview2
@Composable
private fun ButlerIconPreview() {
    PreviewWrapper {
        ButlerMascot(Modifier.size(96.dp))
    }
}