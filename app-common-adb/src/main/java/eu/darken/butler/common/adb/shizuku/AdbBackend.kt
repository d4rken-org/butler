package eu.darken.butler.common.adb.shizuku

/**
 * The server family an ADB access connection speaks to.
 *
 * [label] is a product name and deliberately not translated.
 */
enum class AdbBackend(val label: String) {
    PORTER("Porter"),
    SHIZUKU("Shizuku"),
}
