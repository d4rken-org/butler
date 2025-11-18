package eu.darken.butler.common.theming

import android.annotation.SuppressLint
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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

    // Memoize color scheme to avoid recalculation on every recomposition
    val context = LocalContext.current
    @SuppressLint("NewApi")
    val colors = remember(state, darkTheme, dynamicColors) {
        when {
            dynamicColors && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColors && !darkTheme -> dynamicLightColorScheme(context)
            darkTheme -> ThemeColorProvider.getDarkColorScheme(state.color, state.style)
            else -> ThemeColorProvider.getLightColorScheme(state.color, state.style)
        }
    }

    MaterialTheme(colorScheme = colors, content = content, typography = ButlerTypography)
}

@Preview(showBackground = true, name = "AMOLED Light")
@Composable
fun AmoledLightPreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.DEFAULT, ThemeColor.AMOLED)) { SampleContent() }

@Preview(showBackground = true, name = "AMOLED Dark")
@Composable
fun AmoledDarkPreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.DEFAULT, ThemeColor.AMOLED)) { SampleContent() }