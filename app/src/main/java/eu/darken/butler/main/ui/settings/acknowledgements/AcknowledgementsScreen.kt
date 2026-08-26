package eu.darken.butler.main.ui.settings.acknowledgements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsBaseItem
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import androidx.compose.runtime.collectAsState

@Composable
fun AcknowledgementsScreen(
    state: AcknowledgementsScreenViewModel.State,
    onNavigateUp: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_acknowledgements_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    eu.darken
                                        .butler
                                        .common
                                        .R
                                        .string
                                        .general_back_action
                                )
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                SettingsCategoryHeader(stringResource(R.string.settings_acks_translation_header))
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Translate,
                    title = stringResource(R.string.settings_acks_translate_title),
                    subtitle = stringResource(R.string.settings_acks_translate_desc),
                    onClick = { onOpenUrl("http://crowdin.com/project/butler") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Favorite,
                    title = stringResource(R.string.settings_acks_translators_title),
                    subtitle = stringResource(R.string.settings_acks_translators_people),
                    onClick = { onOpenUrl("http://crowdin.com/project/butler") }
                )
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_acks_thanks_header)) }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_acks_crowdin_title),
                    subtitle = stringResource(R.string.settings_acks_crowdin_desc),
                    onClick = { onOpenUrl("http://crowdin.com/project/butler") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_acks_maxpatchs_title),
                    subtitle = stringResource(R.string.settings_acks_maxpatchs_desc),
                    onClick = { onOpenUrl("https://x.com/maxpatchs") }
                )
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_licenses_label)) }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_android_title),
                    subtitle = stringResource(R.string.acknowledgement_android_subtitle),
                    onClick = { onOpenUrl("https://source.android.com/source/licenses.html") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_kotlin_title),
                    subtitle = stringResource(R.string.acknowledgement_kotlin_subtitle),
                    onClick = { onOpenUrl("https://github.com/JetBrains/kotlin") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_dagger_title),
                    subtitle = stringResource(R.string.acknowledgement_dagger_subtitle),
                    onClick = { onOpenUrl("https://github.com/google/dagger") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_librootjava_title),
                    subtitle = stringResource(R.string.acknowledgement_librootjava_subtitle),
                    onClick = { onOpenUrl("https://github.com/Chainfire/librootjava") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_librootkotlinx_title),
                    subtitle = stringResource(R.string.acknowledgement_librootkotlinx_subtitle),
                    onClick = { onOpenUrl("https://github.com/Mygod/librootkotlinx") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_shizuku_title),
                    subtitle = stringResource(R.string.acknowledgement_shizuku_subtitle),
                    onClick = { onOpenUrl("https://github.com/RikkaApps/Shizuku") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_material_design_icons_title),
                    subtitle = stringResource(R.string.acknowledgement_material_design_icons_subtitle),
                    onClick = { onOpenUrl("https://github.com/Templarian/MaterialDesign") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_lottie_title),
                    subtitle = stringResource(R.string.acknowledgement_lottie_subtitle),
                    onClick = { onOpenUrl("https://github.com/airbnb/lottie-android") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_android_robot_title),
                    subtitle = stringResource(R.string.acknowledgement_android_robot_subtitle),
                    onClick = {
                        onOpenUrl("https://developer.android.com/distribute/tools/promote/brand.html")
                    }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_jetpack_compose_title),
                    subtitle = stringResource(R.string.acknowledgement_jetpack_compose_subtitle),
                    onClick = { onOpenUrl("https://developer.android.com/jetpack/compose") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_jetpack_libraries_title),
                    subtitle = stringResource(R.string.acknowledgement_jetpack_libraries_subtitle),
                    onClick = { onOpenUrl("https://developer.android.com/jetpack") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_kotlin_extensions_title),
                    subtitle = stringResource(R.string.acknowledgement_kotlin_extensions_subtitle),
                    onClick = { onOpenUrl("https://github.com/Kotlin/kotlinx.coroutines") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_coil_title),
                    subtitle = stringResource(R.string.acknowledgement_coil_subtitle),
                    onClick = { onOpenUrl("https://github.com/coil-kt/coil") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_smbj_title),
                    subtitle = stringResource(R.string.acknowledgement_smbj_subtitle),
                    onClick = { onOpenUrl("https://github.com/hierynomus/smbj") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_square_libraries_title),
                    subtitle = stringResource(R.string.acknowledgement_square_libraries_subtitle),
                    onClick = { onOpenUrl("https://square.github.io/") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_accompanist_title),
                    subtitle = stringResource(R.string.acknowledgement_accompanist_subtitle),
                    onClick = { onOpenUrl("https://github.com/google/accompanist") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_reorderable_title),
                    subtitle = stringResource(R.string.acknowledgement_reorderable_subtitle),
                    onClick = { onOpenUrl("https://github.com/Calvin-LL/Reorderable") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_semver_title),
                    subtitle = stringResource(R.string.acknowledgement_semver_subtitle),
                    onClick = { onOpenUrl("https://github.com/z4kn4fein/kotlin-semver") }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    title = stringResource(R.string.acknowledgement_vico_title),
                    subtitle = stringResource(R.string.acknowledgement_vico_subtitle),
                    onClick = { onOpenUrl("https://github.com/patrykandpatrick/vico") }
                )
            }
        }
    }
}

@Composable
fun AcknowledgementsScreenHost(vm: AcknowledgementsScreenViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { state ->
        AcknowledgementsScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onOpenUrl = { url -> vm.openUrl(url) },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AcknowledgementsScreenPreview() {
    AcknowledgementsScreen(
        state = AcknowledgementsScreenViewModel.State(),
        onNavigateUp = {},
        onOpenUrl = { _ -> },
    )
}