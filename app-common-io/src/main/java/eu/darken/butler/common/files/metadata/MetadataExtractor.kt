package eu.darken.butler.common.files.metadata

import eu.darken.butler.common.files.APathLookup

/**
 * Extracts file-specific metadata from [APathLookup].
 *
 * Extractors are called during item creation by engines (SearchEngine, BrowsingEngine)
 * to enrich items with type-specific metadata.
 *
 * Example: ApkMetadataExtractor extracts package info from APK files.
 *
 * @param T The type of metadata this extractor produces
 */
interface MetadataExtractor<T : FileMetadata> {

    /**
     * Check if this extractor can handle the given file.
     *
     * Should be a fast check based on file extension or mime type.
     * Called before [extract] to filter applicable extractors.
     *
     * @param lookup The file to check
     * @return true if this extractor can extract metadata from this file
     */
    fun canHandle(lookup: APathLookup<*>): Boolean

    /**
     * Extract metadata from the file.
     *
     * Called during item creation, so should be reasonably fast.
     * Should respect coroutine cancellation.
     *
     * @param lookup The file to extract metadata from
     * @return Result containing extracted metadata, or failure if extraction failed
     */
    suspend fun extract(lookup: APathLookup<*>): Result<T>

    /**
     * Priority for this extractor when multiple extractors can handle the same file.
     *
     * Higher priority extractors are preferred.
     * Default is 0.
     *
     * @return Priority value (higher = more preferred)
     */
    val priority: Int get() = 0
}
