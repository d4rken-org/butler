package eu.darken.butler.common.files.local

import android.os.Parcelable
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import kotlinx.parcelize.Parcelize
import kotlin.time.Instant

@Parcelize
data class LocalPathLookup(
    override val lookedUp: LocalPath,
    override val fileType: FileType,
    override val size: Long,
    override val modifiedAt: Instant,
    override val target: LocalPath? = null,
) : APathLookup<LocalPath>, Parcelable