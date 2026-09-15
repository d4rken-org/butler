package eu.darken.butler.common.theming

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
    val color: ThemeColor = ThemeColor.GREEN,
    /** An RGB suit color for the mascot, overriding the per-mode default in both modes. */
    val suitColor: Int? = null,
)
