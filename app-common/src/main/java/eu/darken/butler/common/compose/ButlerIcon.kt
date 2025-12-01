package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R

@Composable
fun ButlerIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.butler_icon_description),
    variant: ButlerIconVariant = ButlerIconVariant.NORMAL,
) {
    Image(
        painter = painterResource(
            id = when (variant) {
                ButlerIconVariant.NORMAL -> R.drawable.app_icon_normal
                ButlerIconVariant.HAPPY -> R.drawable.app_icon_happy
                ButlerIconVariant.SAD -> R.drawable.app_icon_sad
                ButlerIconVariant.KO -> R.drawable.app_icon_ko
            }
        ),
        contentDescription = contentDescription,
        modifier = modifier
    )
}

enum class ButlerIconVariant {
    NORMAL,
    HAPPY,
    SAD,
    KO,
}

@Preview2
@Composable
private fun ButlerIconPreview() {
    PreviewWrapper {
        Column {
            ButlerIcon(Modifier.size(64.dp), variant = ButlerIconVariant.NORMAL)
            ButlerIcon(Modifier.size(64.dp), variant = ButlerIconVariant.HAPPY)
            ButlerIcon(Modifier.size(64.dp), variant = ButlerIconVariant.SAD)
            ButlerIcon(Modifier.size(64.dp), variant = ButlerIconVariant.KO)
        }
    }
}