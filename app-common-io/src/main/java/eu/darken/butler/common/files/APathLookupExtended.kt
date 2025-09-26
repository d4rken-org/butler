package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

@Keep
interface APathLookupExtended<out T : APath> : APathLookup<T> {
    val ownership: Ownership?
    val permissions: Permissions?
    val createdAt: Instant?
}