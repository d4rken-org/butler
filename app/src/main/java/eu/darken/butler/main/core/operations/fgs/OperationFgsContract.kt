package eu.darken.butler.main.core.operations.fgs

/**
 * Intent actions and extras shared between [OperationNotifications] (which builds the
 * PendingIntents), [MainActivity] (which routes notification taps) and [OperationCancelReceiver].
 *
 * All PendingIntents use explicit component targets — there are NO matching manifest intent-filters,
 * so the exported [MainActivity] is not reachable via these internal actions from other apps.
 */
internal const val ACTION_FOCUS_OPERATION = "eu.darken.butler.intent.action.FOCUS_OPERATION"
internal const val ACTION_CANCEL_OPERATION = "eu.darken.butler.intent.action.CANCEL_OPERATION"

internal const val EXTRA_WORKSPACE_ID = "eu.darken.butler.intent.extra.WORKSPACE_ID"
internal const val EXTRA_OPERATION_ID = "eu.darken.butler.intent.extra.OPERATION_ID"

internal const val NOTIFICATION_CHANNEL_PROGRESS = "operations_progress"
internal const val NOTIFICATION_CHANNEL_ATTENTION = "operations_attention"

/** The foreground-service anchor notification (group summary). */
internal const val NOTIFICATION_ID_SUMMARY = 1_000_000
