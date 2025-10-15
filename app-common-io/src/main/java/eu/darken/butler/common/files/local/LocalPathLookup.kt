package eu.darken.butler.common.files.local

import android.os.Parcelable
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.parcelize.Parcelize
import kotlin.time.Instant

@Parcelize
data class LocalPathLookup(
    override val lookedUp: LocalPath,
    override val fileType: FileType,
    override val size: Long,
    override val modifiedAt: Instant,
    override val target: LocalPath? = null,
    override val error: String? = null,
) : APathLookup<LocalPath>, Parcelable