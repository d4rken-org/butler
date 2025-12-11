package eu.darken.butler.common.error

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.navigation.NavigationController

/**
 * Context provided to [HasLocalizedError.getLocalizedError] for constructing navigation-aware
 * or activity-aware error actions.
 */
data class LocalizedErrorContext(
    val activity: Activity? = null,
    val navController: NavigationController? = null,
)

interface HasLocalizedError {
    fun getLocalizedError(context: LocalizedErrorContext): LocalizedError
}

data class LocalizedError(
    val throwable: Throwable,
    val label: CaString,
    val description: CaString,
    val fixActionLabel: CaString? = null,
    val fixAction: (() -> Unit)? = null,
    val infoActionLabel: CaString? = null,
    val infoAction: (() -> Unit)? = null,
) {
    fun asText() = caString { "${label.get(it)}:\n${description.get(it)}" }
}

fun Throwable.localized(
    c: Context,
    errorContext: LocalizedErrorContext = LocalizedErrorContext(),
): LocalizedError = when {
    this is HasLocalizedError -> this.getLocalizedError(errorContext)
    this is ActivityNotFoundException -> LocalizedError(
        throwable = this,
        label = caString { "${c.getString(R.string.general_error_label)} - ${this@localized::class.simpleName!!}" },
        description = caString {
            "${it.getString(R.string.general_error_no_compatible_app_found_msg)}\n$localizedMessage"
        }
    )

    localizedMessage != null -> LocalizedError(
        throwable = this,
        label = caString { "${c.getString(R.string.general_error_label)} - ${this@localized::class.simpleName!!}" },
        description = caString { localizedMessage ?: getStackTracePeek() }
    )

    else -> LocalizedError(
        throwable = this,
        label = caString { "${c.getString(R.string.general_error_label)} - ${this@localized::class.simpleName!!}" },
        description = caString { getStackTracePeek() }
    )
}

internal fun Throwable.getStackTracePeek() = this.stackTraceToString()
    .lines()
    .filterIndexed { index, _ -> index > 1 }
    .take(3)
    .joinToString("\n")