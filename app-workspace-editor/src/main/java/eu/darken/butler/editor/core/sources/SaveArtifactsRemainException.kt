package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.editor.R

/**
 * The file is gone, but artifacts of an interrupted save are still next to it - so the user's
 * content most likely survives inside one of them.
 *
 * Distinct from [eu.darken.butler.common.files.errors.PathNotFoundException] because the recovery
 * story is the opposite: nothing is lost yet. [artifacts] is a field rather than text in the
 * message so a recovery action can consume it without re-scanning or parsing.
 */
class SaveArtifactsRemainException(
    path: APath<*>,
    val artifacts: List<APath<*>>,
) : ReadException(message = "Path does not exist, ${artifacts.size} save artifact(s) remain", path = path),
    PathGoneError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_error_save_artifacts_label.toCaString(),
        description = caString {
            val resolved = path!!
            it.getString(
                R.string.editor_error_save_artifacts_description,
                resolved.name.ifBlank { resolved.path },
                artifacts.joinToString { artifact -> artifact.name },
            )
        },
    )
}
