package eu.darken.butler.common.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeColorProvider
import eu.darken.butler.common.theming.ThemeSeed
import eu.darken.butler.common.theming.ThemeStyle

@Composable
fun ThemeColorSelectorDialog(
    title: String,
    selectedOption: ThemeColor,
    customSeed: ThemeSeed,
    onOptionSelected: (ThemeColor) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                // Re-selecting a row still reports it: that is how the already-selected Custom row
                // doubles as "edit my custom theme".
                ThemeColor.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selectedOption,
                            onClick = { onOptionSelected(option) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        ColorPreviewIcon(themeColor = option, customSeed = customSeed)

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = option.label.get(context),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.general_cancel_action))
            }
        }
    )
}

@Composable
private fun ColorPreviewIcon(themeColor: ThemeColor, customSeed: ThemeSeed) {
    val (lightColor, darkColor) = remember(themeColor, customSeed) {
        val seed = themeColor.preset ?: customSeed
        ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark = false).primary to
            ThemeColorProvider.getColorScheme(seed, ThemeStyle.DEFAULT, dark = true).primary
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Light mode color circle
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(lightColor)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )

        // Dark mode color circle
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(darkColor)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )
    }
}