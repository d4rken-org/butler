package eu.darken.butler.workspace.contracts.searcher

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.R
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
sealed class SearchTarget : Parcelable {
    abstract val enabled: Boolean

    /** Backend-agnostic label for progress rows, chips, and history entries. */
    abstract val displayText: CaString

    /**
     * Identity for dedup and add/remove/toggle matching: two targets with equal identity are
     * "the same target" regardless of mutable state like [enabled] or display labels.
     */
    abstract val identity: Any

    @Serializable
    @SerialName("path")
    @Parcelize
    data class Path(
        val path: APath<*>,
        override val enabled: Boolean = true,
        val label: String? = null,
    ) : SearchTarget() {

        @IgnoredOnParcel
        override val displayText: CaString
            get() = label?.toCaString() ?: path.userReadablePath

        @IgnoredOnParcel
        override val identity: Any
            get() = path

        companion object {
            fun from(path: APath<*>) = Path(
                path = path,
                enabled = true,
                label = null,
            )
        }
    }

    @Serializable
    @SerialName("mediastore")
    @Parcelize
    data class MediaStore(
        val collection: Collection,
        override val enabled: Boolean = true,
    ) : SearchTarget() {

        @IgnoredOnParcel
        override val displayText: CaString
            get() = when (collection) {
                Collection.IMAGES -> R.string.workspace_searcher_target_photos.toCaString()
                Collection.VIDEO -> R.string.workspace_searcher_target_videos.toCaString()
                Collection.AUDIO -> R.string.workspace_searcher_target_music.toCaString()
                Collection.DOWNLOADS -> R.string.workspace_searcher_target_downloads.toCaString()
            }

        @IgnoredOnParcel
        override val identity: Any
            get() = collection

        @Serializable
        enum class Collection(
            /** Lowest API level on which this collection can be queried. */
            val minApiLevel: Int,
        ) {
            @SerialName("images") IMAGES(26),
            @SerialName("video") VIDEO(26),
            @SerialName("audio") AUDIO(26),

            // MediaStore.Downloads only exists since API 29
            @SerialName("downloads") DOWNLOADS(29),
        }
    }
}
