package eu.darken.butler.common.picker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.darken.butler.common.picker.core.FilePickerConfig
import eu.darken.butler.common.picker.core.FilePickerResult

class FilePickerLauncher(
    private val onResult: (FilePickerResult) -> Unit
) {
    private var _isShowing = mutableStateOf(false)
    private var _config = mutableStateOf(FilePickerConfig())
    
    val isShowing: Boolean get() = _isShowing.value
    val config: FilePickerConfig get() = _config.value
    
    fun launch(
        config: FilePickerConfig = FilePickerConfig(),
        mode: FilePickerMode = FilePickerMode.ADAPTIVE
    ) {
        _config.value = config
        _isShowing.value = true
    }
    
    fun dismiss() {
        _isShowing.value = false
    }
    
    fun handleResult(result: FilePickerResult) {
        onResult(result)
        dismiss()
    }
}

@Composable
fun rememberFilePickerLauncher(
    onResult: (FilePickerResult) -> Unit
): FilePickerLauncher {
    return remember {
        FilePickerLauncher(onResult)
    }
}

@Composable
fun FilePickerHost(
    launcher: FilePickerLauncher,
    mode: FilePickerMode = FilePickerMode.ADAPTIVE,
) {
    if (launcher.isShowing) {
        AdaptiveFilePicker(
            mode = mode,
            config = launcher.config,
            onResult = launcher::handleResult,
            onDismiss = launcher::dismiss
        )
    }
}