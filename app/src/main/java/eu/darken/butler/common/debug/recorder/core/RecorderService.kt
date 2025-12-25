package eu.darken.butler.common.debug.recorder.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.notifications.PendingIntentCompat
import eu.darken.butler.common.ui.Service2
import eu.darken.butler.main.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject


@AndroidEntryPoint
class RecorderService : Service2() {
    private lateinit var builder: NotificationCompat.Builder

    @Inject lateinit var recorderManager: RecorderManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var dispatcherProvider: DispatcherProvider
    private val recorderScope by lazy {
        CoroutineScope(SupervisorJob() + dispatcherProvider.IO)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val compatChannel = NotificationChannelCompat.Builder(
            NOTIF_CHANID_DEBUG, NotificationManagerCompat.IMPORTANCE_MAX
        ).apply {
            setName(getString(R.string.debug_notification_channel_label))
        }.build()

        NotificationManagerCompat.from(this).createNotificationChannel(compatChannel)

        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntentCompat.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getService(
            this,
            0,
            Intent(this, RecorderService::class.java).apply {
                action = STOP_ACTION
            },
            PendingIntentCompat.FLAG_IMMUTABLE
        )

        builder = NotificationCompat.Builder(this, NOTIF_CHANID_DEBUG).apply {
            setChannelId(NOTIF_CHANID_DEBUG)
            setContentIntent(openPi)
            priority = NotificationCompat.PRIORITY_MAX
            setSmallIcon(R.drawable.ic_bug_report)
            setContentText(getString(R.string.debug_log_recording_progress))
            setContentTitle(getString(eu.darken.butler.common.R.string.app_name))
            setOngoing(true)
            addAction(
                NotificationCompat.Action.Builder(
                    0,
                    getString(R.string.debug_log_stop_action),
                    stopPi
                ).build()
            )
        }

        try {
            startForeground(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            log(TAG) { "Halt stop! Service can't go foreground: ${e.asLog()}" }
            runBlocking { recorderManager.stopRecorder() }
        }
        recorderManager.state
            .onEach {
                if (it.isRecording) {
                    builder.apply {
                        setContentTitle(getString(R.string.debug_log_recording_progress))
                        setContentText("${it.currentLogDir?.path}/")
                    }
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            .launchIn(recorderScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log(TAG) { "onStartCommand(intent=$intent, flags=$flags, startId=$startId" }
        if (intent?.action == STOP_ACTION) {
            recorderScope.launch {
                recorderManager.stopRecorder()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        recorderScope.coroutineContext.cancel()
        super.onDestroy()
    }

    companion object {
        private val TAG = logTag("Debug", "Log", "Recorder", "Service")
        private val NOTIF_CHANID_DEBUG = "${BuildConfigWrap.APPLICATION_ID}.notification.channel.debug"
        private const val STOP_ACTION = "STOP_SERVICE"
        private const val NOTIFICATION_ID = 53
    }
}