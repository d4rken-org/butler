package eu.darken.butler.common.picker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import eu.darken.butler.common.picker.core.FilePickerConfig
import eu.darken.butler.common.picker.core.FilePickerResult

// Already defined in FilePickerSimple.kt, so commented out here
// enum class FilePickerMode {
//     FULLSCREEN,
//     BOTTOM_SHEET,
//     ADAPTIVE
// }

@Composable
fun AdaptiveFilePicker(
    mode: FilePickerMode = FilePickerMode.ADAPTIVE,
    config: FilePickerConfig,
    resultKey: String = "file_picker_result",
    onResult: (FilePickerResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    
    val actualMode = when (mode) {
        FilePickerMode.ADAPTIVE -> {
            if (isTablet) {
                FilePickerMode.BOTTOM_SHEET
            } else {
                FilePickerMode.FULLSCREEN
            }
        }
        else -> mode
    }
    
    when (actualMode) {
        FilePickerMode.FULLSCREEN -> {
            FilePickerFullScreen(
                config = config,
                resultKey = resultKey,
                onResult = onResult,
                onBack = onDismiss
            )
        }
        FilePickerMode.BOTTOM_SHEET -> {
            FilePickerBottomSheet(
                config = config,
                resultKey = resultKey,
                onResult = onResult,
                onDismiss = onDismiss
            )
        }
        FilePickerMode.ADAPTIVE -> {
            // This shouldn't happen as ADAPTIVE is resolved above
            FilePickerBottomSheet(
                config = config,
                resultKey = resultKey,
                onResult = onResult,
                onDismiss = onDismiss
            )
        }
    }
}