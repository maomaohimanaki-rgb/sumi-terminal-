package dev.sumi.terminal.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import dev.sumi.terminal.SumiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class TerminalDaemonService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusTickerJob: Job? = null
    private var ipcServerJob: Job? = null
    private var ipcServerSocket: LocalServerSocket? = null
    private val startElapsedRealtime = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquirePermanentWakeLock()
        promoteToForeground()
        startAbstractIpcHookServer()
        startStatusNotificationLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        acquirePermanentWakeLock()
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val startedSpecial = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                true
            }.getOrDefault(false)

            if (!startedSpecial) {
                val startedSync = runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    true
                }.getOrDefault(false)

                if (!startedSync) {
                    runCatching { startForeground(NOTIFICATION_ID, notification) }
                }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    /**
     * Emergency automatic reschedule if user swipes the app away from Recents.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            val restartIntent = Intent(applicationContext, TerminalDaemonService::class.java).apply {
                setPackage(packageName)
            }
            val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    1001,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    1001,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000L,
                restartPendingIntent
            )
        }
    }

    override fun onDestroy() {
        statusTickerJob?.cancel()
        ipcServerJob?.cancel()
        runCatching { ipcServerSocket?.close() }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquirePermanentWakeLock() {
        runCatching {
            val existing = wakeLock
            if (existing != null && existing.isHeld) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            wakeLock = wl
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    /**
     * Abstract UNIX domain socket ("dev.sumi.terminal.hook") receiving notifications
     * from inside the PRoot rootfs (e.g., `ok|Build concluído` or `error|Falha no pacman`).
     */
    private fun startAbstractIpcHookServer() {
        ipcServerJob?.cancel()
        ipcServerJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val server = LocalServerSocket(IPC_SOCKET_NAME)
                ipcServerSocket = server
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        client.use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
                            val rawLine = reader.readLine()?.trim().orEmpty()
                            if (rawLine.isNotEmpty()) {
                                handleIpcHookPayload(rawLine)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleIpcHookPayload(payload: String) {
        val sumiApp = applicationContext as? SumiApp ?: return
        val parts = payload.split("|", limit = 2)
        if (parts.size == 2) {
            val type = parts[0].lowercase(Locale.ROOT)
            val msg = parts[1]
            val isError = type == "error" || type == "err" || type == "fail"
            sumiApp.alertDispatcher.dispatch(
                title = if (isError) "Alerta do Daemon (Erro)" else "Hook IPC Sumi",
                message = msg,
                isError = isError
            )
        } else {
            sumiApp.alertDispatcher.dispatch(
                title = "Hook IPC Sumi",
                message = payload,
                isError = false
            )
        }
    }

    /**
     * Updates the foreground notification every 5 seconds with uptime, heap memory,
     * ADPF thermal state, and active session info, while verifying WakeLock retention.
     */
    private fun startStatusNotificationLoop() {
        statusTickerJob?.cancel()
        statusTickerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                acquirePermanentWakeLock()
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIFICATION_ID, buildStatusNotification())
                }
                delay(5000L)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.terminal_daemon_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.terminal_daemon_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(): Notification {
        val sumiApp = applicationContext as? SumiApp
        val elapsedSec = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000L).coerceAtLeast(0L)
        val hours = elapsedSec / 3600
        val mins = (elapsedSec % 3600) / 60
        val secs = elapsedSec % 60
        val uptimeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val thermalBadge = sumiApp?.performanceManager?.thermalState?.value?.badge ?: "ADPF • 16ms"
        val sessionCount = sumiApp?.sessionManager?.sessions?.value?.size ?: 1
        val activeTitle = sumiApp?.sessionManager?.getActiveSession()?.title?.value ?: "bash"

        val statusLine = "Uptime $uptimeStr • Heap ${usedHeapMb}MB • $thermalBadge • $sessionCount aba(s) [$activeTitle]"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notif)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(getString(R.string.terminal_daemon_notification_title))
            .setContentText(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusLine))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sumi_pty_daemon_v2"
        private const val NOTIFICATION_ID = 4201
        const val WAKELOCK_TAG = "APP:DAEMON_WAKELOCK"
        const val IPC_SOCKET_NAME = "dev.sumi.terminal.hook"
        const val ACTION_STOP = "dev.sumi.terminal.STOP_DAEMON"

        fun startDaemon(context: Context) {
            runCatching {
                val svcIntent = Intent(context, TerminalDaemonService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
        }
    }
}
