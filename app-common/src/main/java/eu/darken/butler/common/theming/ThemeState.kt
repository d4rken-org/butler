package eu.darken.butler.common.theming

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
)
