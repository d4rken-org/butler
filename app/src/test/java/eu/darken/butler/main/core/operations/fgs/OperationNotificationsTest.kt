package eu.darken.butler.main.core.operations.fgs

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Instant

/**
 * Builds real platform notifications, so it needs a Robolectric context and lives in :app where
 * the notification's content intents resolve against the app manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationNotificationsTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val t0 = Instant.fromEpochMilliseconds(0)

    private val description = "Copying 5 files to Download"

    private fun createNotifications(): OperationNotifications = OperationNotifications(
        context = context,
        notificationManager = context.getSystemService(NotificationManager::class.java)!!,
    ).also { it.setupChannels() }

    private fun activeState(): Operation.State.Active = object : Operation.State.Active {
        override val startedAt = t0
        override val primaryProgress = Progress.Data(count = Progress.Count.Percent(1, 2))
        override val secondaryProgress: Progress.Data? = null
    }

    private fun opMetadata(): Operation.Metadata = mockk(relaxed = true) {
        every { origin } returns Operation.Metadata.Origin.Explorer(Workspace.Id())
        every { title } returns "Copy operation".toCaString()
        every { this@mockk.description } returns this@OperationNotificationsTest.description.toCaString()
    }

    private fun managedOp(
        state: Operation.State,
        cancelRequested: Boolean,
    ): ManagedOperation = mockk {
        every { this@mockk.id } returns Operation.Id()
        every { this@mockk.state } returns MutableStateFlow(state)
        every { this@mockk.cancelRequested } returns MutableStateFlow(cancelRequested)
        // A cancel-requested operation can no longer be cancelled again.
        every { canCancel } returns !cancelRequested
        every { metadata } returns opMetadata()
    }

    /** Control: the description reaches the notification while the operation is simply running. */
    @Test
    fun `an active operation carries its description`() {
        val notification = createNotifications().buildProgress(
            notificationId = 1,
            operation = managedOp(activeState(), cancelRequested = false),
            state = activeState(),
        )

        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() shouldBe description
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() shouldBe description
    }

    /**
     * The title is per-kind ("Copy operation"), so with two operations of the same kind running,
     * dropping the description leaves the user unable to tell which one is cancelling.
     */
    @Test
    fun `a cancel-requested operation still carries its description`() {
        val notification = createNotifications().buildProgress(
            notificationId = 2,
            operation = managedOp(activeState(), cancelRequested = true),
            state = activeState(),
        )

        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() shouldBe
            context.getString(R.string.ops_notification_state_cancelling)
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() shouldBe description
    }
}
