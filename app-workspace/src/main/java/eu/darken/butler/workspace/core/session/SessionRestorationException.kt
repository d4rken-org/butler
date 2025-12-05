package eu.darken.butler.workspace.core.session

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.workspace.R
import eu.darken.butler.common.R as CommonR

class SessionRestorationException(
    cause: Throwable,
    private val onRequestClearSession: () -> Unit,
    private val onRequestShareError: () -> Unit,
) : Exception("Session restoration failed", cause), HasLocalizedError {

    override fun getLocalizedError(): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.workspace_session_restoration_error_title.toCaString(),
        description = R.string.workspace_session_restoration_error_description.toCaString(),
        infoActionLabel = CommonR.string.general_share_error_action.toCaString(),
        infoAction = { _ -> onRequestShareError() },
        fixActionLabel = R.string.workspace_session_restoration_error_clear_action.toCaString(),
        fixAction = { _ -> onRequestClearSession() },
    )
}
