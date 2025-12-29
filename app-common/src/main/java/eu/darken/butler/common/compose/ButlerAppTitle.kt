package eu.darken.butler.common.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import eu.darken.butler.common.R

@Composable
fun ButlerAppTitle(
    modifier: Modifier = Modifier,
    isUpgraded: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    if (isUpgraded) {
        ColoredTitleText(
            modifier = modifier,
            fullTitle = stringResource(R.string.app_name_upgraded),
            postfix = stringResource(R.string.app_name_upgrade_postfix),
            style = style,
        )
    } else {
        Text(
            text = stringResource(R.string.app_name),
            modifier = modifier,
            style = style,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview2
@Composable
private fun ButlerAppTitlePreview() {
    PreviewWrapper {
        ButlerAppTitle(isUpgraded = false)
    }
}

@Preview2
@Composable
private fun ButlerAppTitleUpgradedPreview() {
    PreviewWrapper {
        ButlerAppTitle(isUpgraded = true)
    }
}
