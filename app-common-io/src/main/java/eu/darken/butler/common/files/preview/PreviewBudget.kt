package eu.darken.butler.common.files.preview

/**
 * Central caps for generated previews so no source (native, requested, or Coil `Size.ORIGINAL`) can
 * make us allocate an unbounded bitmap and OOM the process.
 *
 * Worst case at [MAX_DIM]=1024 is 1024*1024*4 = 4 MB per ARGB_8888 bitmap.
 */
object PreviewBudget {
    /** Hard cap per bitmap edge for full previews (PDF pages etc.). */
    const val MAX_DIM = 1024

    /** Used when the requested size is unknown/undefined (e.g. Coil Size.ORIGINAL). Never the native size. */
    const val DEFAULT_TARGET = 384

    /** Icons never need to be large; keeps a pathological intrinsic size bounded. */
    const val MAX_ICON_DIM = 256

    /**
     * Clamp a requested edge length into `[1, max]`, substituting [default] when the request is
     * unknown (`<= 0`). This is the single choke point every generator routes its target size through.
     */
    fun resolveEdge(requestedPx: Int, max: Int = MAX_DIM, default: Int = DEFAULT_TARGET): Int {
        val base = if (requestedPx <= 0) default else requestedPx
        return base.coerceIn(1, max)
    }
}
