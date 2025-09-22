package eu.darken.butler.explorer.core.operations

data class SpeedInfo(
    val current: Long,
    val average: Long,
    val peak: Long,
) {
    companion object {
        val UNCHANGED = SpeedInfo(0, 0, 0)
        val NONE = SpeedInfo(0, 0, 0)
    }
}