package eu.darken.butler.common.files.errors

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.io.R

/**
 * A write that failed after the data had already been put somewhere the user cannot guess: a staged
 * replacement lives under a hidden sibling name, so a failure that keeps it has to say so.
 *
 * ```
 * throw DataKeptException(
 *     "Could not replace photo.jpg, data kept as ${staging.name}",
 *     destination,
 *     Recovery.NewData(staging.name),
 *     cause,
 * )
 * ```
 *
 * [message] stays technical for the logs; [recovery] is what the user is shown.
 */
class DataKeptException(
    message: String,
    path: APath<*>,
    val recovery: Recovery,
    cause: Throwable? = null,
) : WriteException(message = message, path = path, cause = cause) {

    /** What survived the failure, named by the sibling file names it survived under. */
    sealed interface Recovery {

        /** The destination holds nothing usable, [newData] is the only copy. */
        data class NewData(val newData: String) : Recovery

        /** The previous file is at the destination (restored, or never moved), [newData] waits beside it. */
        data class OriginalInPlace(val newData: String) : Recovery

        /** Neither is at the destination: the previous file is [original], the new data [newData]. */
        data class BothKept(val original: String, val newData: String) : Recovery

        /** [source] may be intact, which makes [partial] a possibly truncated copy rather than kept data. */
        data class MaybePartial(val source: String, val partial: String) : Recovery

        /**
         * A swap-in that may or may not have gone through: the file is at the destination, still
         * [newData], or [original] where an [original] was renamed aside at all.
         */
        data class Uncertain(val original: String?, val newData: String) : Recovery

        /**
         * A move carried out as a rename plus a reparent, whose reparent threw: [original] is gone
         * from its folder under the name [intermediate], and whether it reached [destination] is no
         * longer knowable. The only recovery that spans two folders, so it names a path.
         */
        data class Intermediate(
            val original: String,
            val intermediate: String,
            val destination: APath<*>,
        ) : Recovery
    }

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.path_action_data_kept_title.toCaString(),
        description = caString { cx ->
            val destination = path!!.userReadablePath.get(cx)
            val sb = StringBuilder()
            sb.append(
                when (val r = recovery) {
                    is Recovery.NewData -> cx.getString(
                        R.string.path_action_data_kept_new_data_description,
                        destination,
                        r.newData,
                    )

                    is Recovery.OriginalInPlace -> cx.getString(
                        R.string.path_action_data_kept_original_in_place_description,
                        destination,
                        r.newData,
                    )

                    is Recovery.BothKept -> cx.getString(
                        R.string.path_action_data_kept_both_description,
                        destination,
                        r.original,
                        r.newData,
                    )

                    is Recovery.MaybePartial -> cx.getString(
                        R.string.path_action_data_kept_maybe_partial_description,
                        destination,
                        r.source,
                        r.partial,
                    )

                    is Recovery.Intermediate -> cx.getString(
                        R.string.path_action_data_kept_intermediate_description,
                        r.original,
                        r.intermediate,
                        r.destination.userReadablePath.get(cx),
                    )

                    is Recovery.Uncertain -> if (r.original != null) {
                        cx.getString(
                            R.string.path_action_data_kept_uncertain_description,
                            destination,
                            r.original,
                            r.newData,
                        )
                    } else {
                        cx.getString(
                            R.string.path_action_data_kept_uncertain_pair_description,
                            destination,
                            r.newData,
                        )
                    }
                }
            )
            cause?.let {
                sb.append("\n\n")
                sb.append(it.localized(cx).asText().get(cx))
            }
            sb.toString()
        }
    )
}
