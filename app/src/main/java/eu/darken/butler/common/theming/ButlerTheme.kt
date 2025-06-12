package eu.darken.butler.common.theming

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.theming.ButlerColors.backgroundDark
import eu.darken.butler.common.theming.ButlerColors.backgroundDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.backgroundDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.backgroundLight
import eu.darken.butler.common.theming.ButlerColors.backgroundLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.backgroundLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.errorContainerDark
import eu.darken.butler.common.theming.ButlerColors.errorContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.errorContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.errorContainerLight
import eu.darken.butler.common.theming.ButlerColors.errorContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.errorContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.errorDark
import eu.darken.butler.common.theming.ButlerColors.errorDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.errorDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.errorLight
import eu.darken.butler.common.theming.ButlerColors.errorLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.errorLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceDark
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceLight
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.inverseOnSurfaceLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryDark
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryLight
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.inversePrimaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceDark
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceLight
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.inverseSurfaceLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onBackgroundDark
import eu.darken.butler.common.theming.ButlerColors.onBackgroundDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onBackgroundDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onBackgroundLight
import eu.darken.butler.common.theming.ButlerColors.onBackgroundLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onBackgroundLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerDark
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerLight
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorDark
import eu.darken.butler.common.theming.ButlerColors.onErrorDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorLight
import eu.darken.butler.common.theming.ButlerColors.onErrorLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onErrorLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryDark
import eu.darken.butler.common.theming.ButlerColors.onPrimaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryLight
import eu.darken.butler.common.theming.ButlerColors.onPrimaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onPrimaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryDark
import eu.darken.butler.common.theming.ButlerColors.onSecondaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryLight
import eu.darken.butler.common.theming.ButlerColors.onSecondaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSecondaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceDark
import eu.darken.butler.common.theming.ButlerColors.onSurfaceDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceLight
import eu.darken.butler.common.theming.ButlerColors.onSurfaceLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantDark
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantLight
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onSurfaceVariantLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryDark
import eu.darken.butler.common.theming.ButlerColors.onTertiaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryLight
import eu.darken.butler.common.theming.ButlerColors.onTertiaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.onTertiaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.outlineDark
import eu.darken.butler.common.theming.ButlerColors.outlineDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.outlineDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.outlineLight
import eu.darken.butler.common.theming.ButlerColors.outlineLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.outlineLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.outlineVariantDark
import eu.darken.butler.common.theming.ButlerColors.outlineVariantDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.outlineVariantDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.outlineVariantLight
import eu.darken.butler.common.theming.ButlerColors.outlineVariantLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.outlineVariantLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.primaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.primaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.primaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.primaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.primaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.primaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.primaryDark
import eu.darken.butler.common.theming.ButlerColors.primaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.primaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.primaryLight
import eu.darken.butler.common.theming.ButlerColors.primaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.primaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.scrimDark
import eu.darken.butler.common.theming.ButlerColors.scrimDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.scrimDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.scrimLight
import eu.darken.butler.common.theming.ButlerColors.scrimLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.scrimLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryDark
import eu.darken.butler.common.theming.ButlerColors.secondaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryLight
import eu.darken.butler.common.theming.ButlerColors.secondaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.secondaryLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightDark
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightLight
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceBrightLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerDark
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighDark
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighLight
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestDark
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestLight
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerHighestLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLight
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowDark
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowLight
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestDark
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestLight
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceContainerLowestLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDark
import eu.darken.butler.common.theming.ButlerColors.surfaceDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDimDark
import eu.darken.butler.common.theming.ButlerColors.surfaceDimDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDimDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDimLight
import eu.darken.butler.common.theming.ButlerColors.surfaceDimLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceDimLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceLight
import eu.darken.butler.common.theming.ButlerColors.surfaceLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantDark
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantLight
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.surfaceVariantLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerDark
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerLight
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryContainerLightMediumContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryDark
import eu.darken.butler.common.theming.ButlerColors.tertiaryDarkHighContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryDarkMediumContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryLight
import eu.darken.butler.common.theming.ButlerColors.tertiaryLightHighContrast
import eu.darken.butler.common.theming.ButlerColors.tertiaryLightMediumContrast

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

private val mediumContrastLightColorScheme =
    lightColorScheme(
        primary = primaryLightMediumContrast,
        onPrimary = onPrimaryLightMediumContrast,
        primaryContainer = primaryContainerLightMediumContrast,
        onPrimaryContainer = onPrimaryContainerLightMediumContrast,
        secondary = secondaryLightMediumContrast,
        onSecondary = onSecondaryLightMediumContrast,
        secondaryContainer = secondaryContainerLightMediumContrast,
        onSecondaryContainer = onSecondaryContainerLightMediumContrast,
        tertiary = tertiaryLightMediumContrast,
        onTertiary = onTertiaryLightMediumContrast,
        tertiaryContainer = tertiaryContainerLightMediumContrast,
        onTertiaryContainer = onTertiaryContainerLightMediumContrast,
        error = errorLightMediumContrast,
        onError = onErrorLightMediumContrast,
        errorContainer = errorContainerLightMediumContrast,
        onErrorContainer = onErrorContainerLightMediumContrast,
        background = backgroundLightMediumContrast,
        onBackground = onBackgroundLightMediumContrast,
        surface = surfaceLightMediumContrast,
        onSurface = onSurfaceLightMediumContrast,
        surfaceVariant = surfaceVariantLightMediumContrast,
        onSurfaceVariant = onSurfaceVariantLightMediumContrast,
        outline = outlineLightMediumContrast,
        outlineVariant = outlineVariantLightMediumContrast,
        scrim = scrimLightMediumContrast,
        inverseSurface = inverseSurfaceLightMediumContrast,
        inverseOnSurface = inverseOnSurfaceLightMediumContrast,
        inversePrimary = inversePrimaryLightMediumContrast,
        surfaceDim = surfaceDimLightMediumContrast,
        surfaceBright = surfaceBrightLightMediumContrast,
        surfaceContainerLowest = surfaceContainerLowestLightMediumContrast,
        surfaceContainerLow = surfaceContainerLowLightMediumContrast,
        surfaceContainer = surfaceContainerLightMediumContrast,
        surfaceContainerHigh = surfaceContainerHighLightMediumContrast,
        surfaceContainerHighest = surfaceContainerHighestLightMediumContrast,
    )

private val highContrastLightColorScheme = lightColorScheme(
    primary = primaryLightHighContrast,
    onPrimary = onPrimaryLightHighContrast,
    primaryContainer = primaryContainerLightHighContrast,
    onPrimaryContainer = onPrimaryContainerLightHighContrast,
    secondary = secondaryLightHighContrast,
    onSecondary = onSecondaryLightHighContrast,
    secondaryContainer = secondaryContainerLightHighContrast,
    onSecondaryContainer = onSecondaryContainerLightHighContrast,
    tertiary = tertiaryLightHighContrast,
    onTertiary = onTertiaryLightHighContrast,
    tertiaryContainer = tertiaryContainerLightHighContrast,
    onTertiaryContainer = onTertiaryContainerLightHighContrast,
    error = errorLightHighContrast,
    onError = onErrorLightHighContrast,
    errorContainer = errorContainerLightHighContrast,
    onErrorContainer = onErrorContainerLightHighContrast,
    background = backgroundLightHighContrast,
    onBackground = onBackgroundLightHighContrast,
    surface = surfaceLightHighContrast,
    onSurface = onSurfaceLightHighContrast,
    surfaceVariant = surfaceVariantLightHighContrast,
    onSurfaceVariant = onSurfaceVariantLightHighContrast,
    outline = outlineLightHighContrast,
    outlineVariant = outlineVariantLightHighContrast,
    scrim = scrimLightHighContrast,
    inverseSurface = inverseSurfaceLightHighContrast,
    inverseOnSurface = inverseOnSurfaceLightHighContrast,
    inversePrimary = inversePrimaryLightHighContrast,
    surfaceDim = surfaceDimLightHighContrast,
    surfaceBright = surfaceBrightLightHighContrast,
    surfaceContainerLowest = surfaceContainerLowestLightHighContrast,
    surfaceContainerLow = surfaceContainerLowLightHighContrast,
    surfaceContainer = surfaceContainerLightHighContrast,
    surfaceContainerHigh = surfaceContainerHighLightHighContrast,
    surfaceContainerHighest = surfaceContainerHighestLightHighContrast,
)

private val mediumContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkMediumContrast,
    onPrimary = onPrimaryDarkMediumContrast,
    primaryContainer = primaryContainerDarkMediumContrast,
    onPrimaryContainer = onPrimaryContainerDarkMediumContrast,
    secondary = secondaryDarkMediumContrast,
    onSecondary = onSecondaryDarkMediumContrast,
    secondaryContainer = secondaryContainerDarkMediumContrast,
    onSecondaryContainer = onSecondaryContainerDarkMediumContrast,
    tertiary = tertiaryDarkMediumContrast,
    onTertiary = onTertiaryDarkMediumContrast,
    tertiaryContainer = tertiaryContainerDarkMediumContrast,
    onTertiaryContainer = onTertiaryContainerDarkMediumContrast,
    error = errorDarkMediumContrast,
    onError = onErrorDarkMediumContrast,
    errorContainer = errorContainerDarkMediumContrast,
    onErrorContainer = onErrorContainerDarkMediumContrast,
    background = backgroundDarkMediumContrast,
    onBackground = onBackgroundDarkMediumContrast,
    surface = surfaceDarkMediumContrast,
    onSurface = onSurfaceDarkMediumContrast,
    surfaceVariant = surfaceVariantDarkMediumContrast,
    onSurfaceVariant = onSurfaceVariantDarkMediumContrast,
    outline = outlineDarkMediumContrast,
    outlineVariant = outlineVariantDarkMediumContrast,
    scrim = scrimDarkMediumContrast,
    inverseSurface = inverseSurfaceDarkMediumContrast,
    inverseOnSurface = inverseOnSurfaceDarkMediumContrast,
    inversePrimary = inversePrimaryDarkMediumContrast,
    surfaceDim = surfaceDimDarkMediumContrast,
    surfaceBright = surfaceBrightDarkMediumContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkMediumContrast,
    surfaceContainerLow = surfaceContainerLowDarkMediumContrast,
    surfaceContainer = surfaceContainerDarkMediumContrast,
    surfaceContainerHigh = surfaceContainerHighDarkMediumContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkMediumContrast,
)

private val highContrastDarkColorScheme = darkColorScheme(
    primary = primaryDarkHighContrast,
    onPrimary = onPrimaryDarkHighContrast,
    primaryContainer = primaryContainerDarkHighContrast,
    onPrimaryContainer = onPrimaryContainerDarkHighContrast,
    secondary = secondaryDarkHighContrast,
    onSecondary = onSecondaryDarkHighContrast,
    secondaryContainer = secondaryContainerDarkHighContrast,
    onSecondaryContainer = onSecondaryContainerDarkHighContrast,
    tertiary = tertiaryDarkHighContrast,
    onTertiary = onTertiaryDarkHighContrast,
    tertiaryContainer = tertiaryContainerDarkHighContrast,
    onTertiaryContainer = onTertiaryContainerDarkHighContrast,
    error = errorDarkHighContrast,
    onError = onErrorDarkHighContrast,
    errorContainer = errorContainerDarkHighContrast,
    onErrorContainer = onErrorContainerDarkHighContrast,
    background = backgroundDarkHighContrast,
    onBackground = onBackgroundDarkHighContrast,
    surface = surfaceDarkHighContrast,
    onSurface = onSurfaceDarkHighContrast,
    surfaceVariant = surfaceVariantDarkHighContrast,
    onSurfaceVariant = onSurfaceVariantDarkHighContrast,
    outline = outlineDarkHighContrast,
    outlineVariant = outlineVariantDarkHighContrast,
    scrim = scrimDarkHighContrast,
    inverseSurface = inverseSurfaceDarkHighContrast,
    inverseOnSurface = inverseOnSurfaceDarkHighContrast,
    inversePrimary = inversePrimaryDarkHighContrast,
    surfaceDim = surfaceDimDarkHighContrast,
    surfaceBright = surfaceBrightDarkHighContrast,
    surfaceContainerLowest = surfaceContainerLowestDarkHighContrast,
    surfaceContainerLow = surfaceContainerLowDarkHighContrast,
    surfaceContainer = surfaceContainerDarkHighContrast,
    surfaceContainerHigh = surfaceContainerHighDarkHighContrast,
    surfaceContainerHighest = surfaceContainerHighestDarkHighContrast,
)

@Composable
fun MyAppTheme(state: ThemeState = ThemeState(), content: @Composable () -> Unit) {
    val dynamicColors = when (state.style) {
        ThemeStyle.DEFAULT -> false
        ThemeStyle.MATERIAL_YOU -> hasApiLevel(31)
        ThemeStyle.MEDIUM_CONTRAST -> false
        ThemeStyle.HIGH_CONTRAST -> false
    }
    val darkTheme = when (state.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    @SuppressLint("NewApi")
    val colors = when {
        dynamicColors && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColors && !darkTheme -> dynamicLightColorScheme(LocalContext.current)

        state.style == ThemeStyle.MEDIUM_CONTRAST && darkTheme -> mediumContrastDarkColorScheme
        state.style == ThemeStyle.MEDIUM_CONTRAST && !darkTheme -> mediumContrastLightColorScheme
        state.style == ThemeStyle.HIGH_CONTRAST && darkTheme -> highContrastDarkColorScheme
        state.style == ThemeStyle.HIGH_CONTRAST && !darkTheme -> highContrastLightColorScheme

        darkTheme -> darkScheme
        else -> lightScheme
    }
    MaterialTheme(colorScheme = colors, content = content, typography = ButlerTypography)
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun LightThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.DEFAULT)) { SampleContent() }

@Preview(showBackground = true, name = "Dark Mode")
@Composable
fun DarkThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.DEFAULT)) { SampleContent() }

@Preview(showBackground = true, name = "Material You Light Mode")
@Composable
fun MaterialYouLightThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.MATERIAL_YOU)) { SampleContent() }

@Preview(showBackground = true, name = "Material You Dark Mode")
@Composable
fun MaterialYouDarkThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.MATERIAL_YOU)) { SampleContent() }

@Preview(showBackground = true, name = "Medium Contrast Light Mode")
@Composable
fun MediumContrastLightThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.MEDIUM_CONTRAST)) { SampleContent() }

@Preview(showBackground = true, name = "Medium Contrast Dark Mode")
@Composable
fun MediumContrastDarkThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.MEDIUM_CONTRAST)) { SampleContent() }

@Preview(showBackground = true, name = "High Contrast Light Mode")
@Composable
fun HighContrastLightThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.LIGHT, ThemeStyle.HIGH_CONTRAST)) { SampleContent() }

@Preview(showBackground = true, name = "High Contrast Dark Mode")
@Composable
fun HighContrastDarkThemePreview() =
    MyAppTheme(ThemeState(ThemeMode.DARK, ThemeStyle.HIGH_CONTRAST)) { SampleContent() }
