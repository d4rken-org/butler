package eu.darken.butler.main.core.operations.fgs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.main.ui.MainActivity
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import javax.inject.Inject
import javax.inject.Singleton
import eu.darken.butler.common.R as CommonR

/**
 * Pure builder mapping operation state to platform [android.app.Notification]s. Holds no mutable
 * state — notification IDs and active-counts are supplied by [OperationFgsCoordinator]. Kept free
 * of service/lifecycle dependencies so it is unit-testable with a Robolectric context.
 *
 * Two channels are used because a notification's channel is fixed at first post: progress updates
 * live on a silent [NOTIFICATION_CHANNEL_PROGRESS]; attention (waiting/failed) notifications live
 * on an alerting [NOTIFICATION_CHANNEL_ATTENTION] under a DISTINCT notification ID.
 */
@Singleton
class OperationNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) {

    fun setupChannels() {
        NotificationChannel(
            NOTIFICATION_CHANNEL_PROGRESS,
            context.getString(R.string.ops_notification_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.ops_notification_channel_progress_desc)
            setShowBadge(false)
        }.let { notificationManager.createNotificationChannel(it) }

        NotificationChannel(
            NOTIFICATION_CHANNEL_ATTENTION,
            context.getString(R.string.ops_notification_channel_attention_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ops_notification_channel_attention_desc)
        }.let { notificationManager.createNotificationChannel(it) }
    }

    /** The foreground-service anchor / group summary. */
    fun buildSummary(activeCount: Int): android.app.Notification =
        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_operations)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.ops_notification_summary_title,
                    activeCount,
                    activeCount,
                )
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openMainIntent())
            .build()

    /** Progress notification (Queued / Active) on the silent channel. */
    fun buildProgress(
        notificationId: Int,
        operation: ManagedOperation,
        state: Operation.State,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_operations)
            .setContentTitle(operation.metadata.title.get(context))
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(focusWorkspaceIntent(notificationId, operation))

        when (state) {
            is Operation.State.Active -> {
                // The title is per-kind ("Copy operation"), so with several operations running
                // it cannot tell them apart. The metadata description names what is being moved
                // and where, which is what makes a row identifiable, and what makes its Cancel
                // action safe to press.
                val description = operation.metadata.description.get(context)
                builder.setContentText(description)
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(description))
                applyProgress(builder, state.primaryProgress.count)
            }
            else -> {
                // Queued (or any non-active, non-waiting transient state)
                builder.setContentText(context.getString(R.string.ops_notification_state_queued))
                builder.setProgress(0, 0, true)
            }
        }

        if (operation.canCancel) builder.addCancelAction(notificationId, operation.id)

        return builder.build()
    }

    /** Attention notification for a [Operation.State.Waiting] operation, on the alerting channel. */
    fun buildAttention(
        notificationId: Int,
        operation: ManagedOperation,
    ): android.app.Notification {
        // What the operation itself says it is waiting for: not every wait is a file conflict, and
        // an install confirmation announced as one misdescribes what is being asked.
        val text = (operation.state.value as? Operation.State.Waiting)?.reason?.get(context)
            ?: context.getString(R.string.ops_notification_state_attention_text)
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification_operations)
            .setContentTitle(
                context.getString(R.string.ops_notification_state_attention_title) +
                    " — " + operation.metadata.title.get(context)
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(resolveConflictIntent(notificationId, operation))
            .apply { if (operation.canCancel) addCancelAction(notificationId, operation.id) }
            .build()
    }

    /** Dismissible terminal notification for a failed operation. */
    fun buildFailure(
        notificationId: Int,
        snapshot: CompletedOperationSnapshot,
    ): android.app.Notification =
        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification_operations)
            .setContentTitle(
                context.getString(R.string.ops_notification_result_failed_title) +
                    " — " + snapshot.metadata.title.get(context)
            )
            .setContentText(snapshot.state.summary.get(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(snapshot.state.summary.get(context)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(focusWorkspaceIntent(notificationId, snapshot.metadata.origin.workspaceId))
            .build()

    private fun applyProgress(builder: NotificationCompat.Builder, count: Progress.Count) {
        when (count) {
            is Progress.Count.Indeterminate, is Progress.Count.None -> builder.setProgress(0, 0, true)
            else -> {
                val percent = (count.percentage.coerceIn(0f, 1f) * 100).toInt()
                builder.setProgress(100, percent, false)
                builder.setSubText(count.displayValue.get(context))
            }
        }
    }

    private fun NotificationCompat.Builder.addCancelAction(
        notificationId: Int,
        operationId: Operation.Id,
    ) {
        addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_close_24,
                context.getString(CommonR.string.general_cancel_action),
                cancelIntent(notificationId, operationId),
            ).build()
        )
    }

    private fun openMainIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID_SUMMARY,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PI_FLAGS,
    )

    private fun focusWorkspaceIntent(
        requestCode: Int,
        operation: ManagedOperation,
    ): PendingIntent = focusWorkspaceIntent(requestCode, operation.metadata.origin.workspaceId)

    private fun focusWorkspaceIntent(
        requestCode: Int,
        workspaceId: eu.darken.butler.workspace.core.Workspace.Id,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_FOCUS_OPERATION
            putExtra(EXTRA_WORKSPACE_ID, workspaceId.longTag)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PI_FLAGS,
    )

    private fun resolveConflictIntent(
        requestCode: Int,
        operation: ManagedOperation,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_FOCUS_OPERATION
            putExtra(EXTRA_WORKSPACE_ID, operation.metadata.origin.workspaceId.longTag)
            putExtra(EXTRA_OPERATION_ID, operation.id.longTag)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PI_FLAGS,
    )

    private fun cancelIntent(
        requestCode: Int,
        operationId: Operation.Id,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        // Distinct request-code space from the activity PendingIntents (which use the same id).
        requestCode xor CANCEL_REQUEST_MASK,
        Intent(context, OperationCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_OPERATION
            putExtra(EXTRA_OPERATION_ID, operationId.longTag)
        },
        PI_FLAGS,
    )

    companion object {
        private const val GROUP_KEY = "eu.darken.butler.operations"
        private const val CANCEL_REQUEST_MASK = 0x4000_0000
        private val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
