package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R

/**
 * Replace-all was refused because the document has more matches than [cap] (or their combined
 * text exceeds the memory bound) - BEFORE anything was materialized or mutated. A partial
 * replace is never performed; narrowing the query is the way forward.
 */
class TooManyMatchesException(
    val cap: Int,
    cause: Throwable? = null,
) : IllegalStateException("Replace all refused: more than $cap matches or too much matched text", cause),
    HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_search_too_many_matches_label.toCaString(),
        description = caString {
            it.getString(R.string.editor_search_too_many_matches, cap)
        },
    )
}
