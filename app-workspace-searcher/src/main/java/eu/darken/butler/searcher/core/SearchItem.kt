package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.FileMetadata
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlin.time.Instant

sealed interface SearchItem : Parcelable {
    val lookup: APathLookup<*>
    val matchedQuery: String
    val matchContext: MatchContext?
    val metadata: FileMetadata?

    // Convenience properties (like ExplorerItem)
    val path: APath<*> get() = lookup.lookedUp
    val name: String get() = lookup.lookedUp.name
    val fileType: FileType get() = lookup.fileType
    val size: Long? get() = lookup.size
    val modifiedAt: Instant? get() = lookup.modifiedAt

    sealed interface Directory : SearchItem

    sealed interface File : SearchItem

    @Parcelize
    data class RegularDirectory(
        override val lookup: @RawValue APathLookup<*>,
        override val matchedQuery: String,
        override val matchContext: MatchContext? = null,
        override val metadata: @RawValue FileMetadata? = null,
    ) : Directory

    @Parcelize
    data class RegularFile(
        override val lookup: @RawValue APathLookup<*>,
        override val matchedQuery: String,
        override val matchContext: MatchContext? = null,
        override val metadata: @RawValue FileMetadata? = null,
    ) : File

    @Parcelize
    data class MatchContext(
        val lineNumber: Int? = null,
        val matchedLine: String? = null,
        val startIndex: Int? = null,
        val endIndex: Int? = null
    ) : Parcelable

    companion object {
        fun fromLookup(
            lookup: APathLookup<*>,
            matchedQuery: String,
            matchContext: MatchContext? = null,
            metadata: FileMetadata? = null,
        ): SearchItem {
            return when (lookup.fileType) {
                FileType.DIRECTORY -> RegularDirectory(
                    lookup = lookup,
                    matchedQuery = matchedQuery,
                    matchContext = matchContext,
                    metadata = metadata,
                )
                else -> RegularFile(
                    lookup = lookup,
                    matchedQuery = matchedQuery,
                    matchContext = matchContext,
                    metadata = metadata,
                )
            }
        }
    }
}