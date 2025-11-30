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
fun ButlerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.butler_mascot_description),
    variant: ButlerMascotMode = ButlerMascotMode.Static.Normal
) {
    when (variant) {
        is ButlerMascotMode.Static -> Image(
            painter = painterResource(
                id = when (variant) {
                    ButlerMascotMode.Static.Normal -> R.drawable.mascot_normal
                    ButlerMascotMode.Static.Happy -> R.drawable.mascot_happy
                    ButlerMascotMode.Static.Sad -> R.drawable.mascot_sad
                    ButlerMascotMode.Static.Ko -> R.drawable.mascot_ko
                }
            ),
            contentDescription = contentDescription,
            modifier = modifier
        )

        is ButlerMascotMode.Animated -> TODO()
    }
}

sealed interface ButlerMascotMode {
    sealed interface Static : ButlerMascotMode {
        data object Normal : Static
        data object Happy : Static
        data object Sad : Static
        data object Ko : Static
    }

    sealed interface Animated : ButlerMascotMode {
        data object Random : Animated
        data object Wink : Animated
    }
}


@Preview2
@Composable
private fun ButlerIconPreview() {
    PreviewWrapper {
        Column {
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Normal)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Happy)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Sad)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Ko)
        }
    }
}