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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.theming.ThemeColor

@Composable
fun ThemeColorSelectorDialog(
    title: String,
    selectedOption: ThemeColor,
    onOptionSelected: (ThemeColor) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
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

                        ColorPreviewIcon(themeColor = option)

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
private fun ColorPreviewIcon(themeColor: ThemeColor) {
    val (lightColor, darkColor) = when (themeColor) {
        ThemeColor.GREEN -> Color(0xFF2E7D32) to Color(0xFF8AD68D)
        ThemeColor.BLUE -> Color(0xFF1565C0) to Color(0xFFA6C8FF)
        ThemeColor.AMOLED -> Color(0xFFE65100) to Color(0xFFFFB74D)
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