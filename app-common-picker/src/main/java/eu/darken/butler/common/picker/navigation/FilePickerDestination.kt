package eu.darken.butler.common.picker.navigation

import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.picker.core.FilePickerConfig
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class FilePickerDestination(
    val config: FilePickerConfig,
    val resultKey: String = "file_picker_result_${Uuid.random()}",
) : NavigationDestination