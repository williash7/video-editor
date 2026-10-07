package com.haessentz.videoeditor.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.haessentz.videoeditor.MainActivity
import com.haessentz.videoeditor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class JobInfo(
    val id: String,
    val title: String,
    val status: JobStatus,
    val progress: Float = -1f,
    val detail: String = "",
    val projectId: String? = null,
    val kind: String = "",
) {
    val active get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

class JobScope(val id: String) {
    fun progress(p: Float, detail: String = "") = JobManager.update(id, p, detail)
    fun onCancel(hook: () -> Unit) = JobManager.setHook(id, hook)
}

/** Runs long jobs one after another, in the background, with a foreground notification. */
object JobManager {
    private val _jobs = MutableStateFlow<List<JobInfo>>(emptyList())
    val jobs: StateFlow<List<JobInfo>> = _jobs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val hooks = ConcurrentHashMap<String, () -> Unit>()

    fun enqueue(ctx: Context, title: String, projectId: String?, kind: String = "", block: suspend (JobScope) -> Unit): String {
        val id = UUID.randomUUID().toString()
        modify { it + JobInfo(id, title, JobStatus.QUEUED, projectId = projectId, kind = kind) }
        ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, WorkService::class.java))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                mutex.withLock {
                    set(id) { it.copy(status = JobStatus.RUNNING) }
                    block(JobScope(id))
                }
                set(id) { it.copy(status = JobStatus.DONE, progress = 1f, detail = "") }
            } catch (e: CancellationException) {
                set(id) { it.copy(status = JobStatus.CANCELLED, detail = "בוטל") }
            } catch (e: Throwable) {
                Log.e("JobManager", "job failed", e)
                set(id) { it.copy(status = JobStatus.FAILED, detail = e.message ?: e.javaClass.simpleName) }
            } finally {
                running.remove(id)
                hooks.remove(id)
            }
        }
        running[id] = job
        job.start()
        return id
    }

    fun cancel(id: String) {
        hooks[id]?.invoke()
        running[id]?.cancel()
    }

    fun dismiss(id: String) = modify { list -> list.filter { it.id != id || it.active } }

    fun update(id: String, progress: Float, detail: String) = set(id) { it.copy(progress = progress, detail = detail) }
    fun setHook(id: String, hook: () -> Unit) { hooks[id] = hook }

    private fun set(id: String, f: (JobInfo) -> JobInfo) = modify { list -> list.map { if (it.id == id) f(it) else it } }

    @Synchronized
    private fun modify(f: (List<JobInfo>) -> List<JobInfo>) {
        // keep the list short: drop old finished jobs
        val n = f(_jobs.value)
        val finished = n.filter { !it.active }
        _jobs.value = if (finished.size > 6) n - finished.take(finished.size - 6).toSet() else n
    }
}

class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startFg(build("מתחיל…", -1f))
        if (!started) {
            started = true
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoeditor:work")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            scope.launch {
                JobManager.jobs.collect { list ->
                    val cur = list.firstOrNull { it.status == JobStatus.RUNNING } ?: list.firstOrNull { it.status == JobStatus.QUEUED }
                    if (cur == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    } else {
                        val queued = list.count { it.active } - 1
                        val text = buildString {
                            append(cur.detail.ifEmpty { "עובד…" })
                            if (queued > 0) append(" · עוד $queued בתור")
                        }
                        nm().notify(NOTIF_ID, build(cur.title, cur.progress, text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun startFg(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIF_ID, n, type)
    }

    private fun build(title: String, progress: Float, text: String = ""): Notification {
        ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), progress < 0)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jobs"
        const val NOTIF_ID = 42
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "עבודות ברקע", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
