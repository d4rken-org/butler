package eu.darken.butler.common.theming

import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.hasApiLevel

@Composable
fun MyAppTheme(state: ThemeState = ThemeState(), content: @Composable () -> Unit) {
    val dynamicColors = when (state.style) {
        ThemeStyle.MATERIAL_YOU -> hasApiLevel(31)
        else -> false
    }
    
    val darkTheme = when (state.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !darkTheme
        insetsController.isAppearanceLightNavigationBars = !darkTheme
    }
    
    @SuppressLint("NewApi")
    val colors = when {
        dynamicColors && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColors && !darkTheme -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> ThemeColorProvider.getDarkColorScheme(state.color, state.style)
        else -> ThemeColorProvider.getLightColorScheme(state.color, state.style)
    }
    
    MaterialTheme(colorScheme = colors, content = content, typography = ButlerTypography)
}

// Preview functions for different theme combinations
@Preview(showBackground = true, name = "Teal Light")
@Composable
fun TealLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.DEFAULT, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "Teal Dark")
@Composable
fun TealDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.DEFAULT, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "Purple Light")
@Composable
fun PurpleLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.DEFAULT, ThemeColor.PURPLE)) { SampleContent() }

@Preview(showBackground = true, name = "Purple Dark")
@Composable
fun PurpleDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.DEFAULT, ThemeColor.PURPLE)) { SampleContent() }

@Preview(showBackground = true, name = "Material You Light")
@Composable
fun MaterialYouLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.MATERIAL_YOU, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "Material You Dark")
@Composable
fun MaterialYouDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.MATERIAL_YOU, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "Medium Contrast Light")
@Composable
fun MediumContrastLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.MEDIUM_CONTRAST, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "Medium Contrast Dark")
@Composable
fun MediumContrastDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.MEDIUM_CONTRAST, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "High Contrast Light")
@Composable
fun HighContrastLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.HIGH_CONTRAST, ThemeColor.TEAL)) { SampleContent() }

@Preview(showBackground = true, name = "High Contrast Dark")
@Composable
fun HighContrastDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.HIGH_CONTRAST, ThemeColor.TEAL)) { SampleContent() }