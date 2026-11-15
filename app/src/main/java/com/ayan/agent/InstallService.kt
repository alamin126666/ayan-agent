package com.ayan.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ayan.agent.data.InstallResult
import com.ayan.agent.data.InstallState
import com.ayan.agent.data.RuntimeInstaller
import com.ayan.agent.data.Runtimes
import com.ayan.agent.data.SettingsStore
import com.ayan.agent.data.TermuxBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the entire runtime-installation flow so it
 * keeps running when the user switches apps or the screen turns off.
 *
 * Notification format (example):
 *   "Python ইনস্টল হচ্ছে… (2/5)  ████░░ 42%"
 *
 * The Setup screen binds to this service and observes the same StateFlows
 * to show live progress inside the app while it is in the foreground.
 *
 * Usage:
 *   val intent = Intent(ctx, InstallService::class.java).apply {
 *       putStringArrayListExtra(EXTRA_IDS, ArrayList(selectedIds))
 *   }
 *   ctx.startForegroundService(intent)
 *   ctx.bindService(intent, connection, BIND_AUTO_CREATE)
 */
class InstallService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): InstallService = this@InstallService
    }

    private val binder = LocalBinder()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── State the Setup screen observes ──────────────────────────────────────
    val bootstrapState    = MutableStateFlow(InstallState.NOT_INSTALLED)
    val bootstrapProgress = MutableStateFlow(0f)
    /** Install state per runtime id */
    val states    = MutableStateFlow(Runtimes.all.associate { it.id to InstallState.NOT_INSTALLED })
    /** Progress (0..1) per runtime id */
    val progressF = MutableStateFlow(Runtimes.all.associate { it.id to 0f })
    val log       = MutableStateFlow(emptyList<String>())
    val isInstalling = MutableStateFlow(false)
    val isDone       = MutableStateFlow(false)
    val pkgMissing   = MutableStateFlow(false)
    // ─────────────────────────────────────────────────────────────────────────

    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotif("ইনস্টলেশন শুরু হচ্ছে…", 0, 0, ind = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ids = intent?.getStringArrayListExtra(EXTRA_IDS) ?: return START_NOT_STICKY
        // Guard: don't restart if still running (e.g. user taps retry while running)
        if (!isInstalling.value) scope.launch { runInstall(ids) }
        return START_NOT_STICKY
    }

    // ── Core install flow ─────────────────────────────────────────────────────

    private suspend fun runInstall(selectedIds: List<String>) {
        isInstalling.value = true

        // ── Step 1: Termux bootstrap ──────────────────────────────────────────
        if (!TermuxBootstrap.isInstalled(filesDir)) {
            bootstrapState.value = InstallState.INSTALLING
            bootstrapProgress.value = 0f
            updateNotif("Termux runtime ডাউনলোড হচ্ছে…", 0, 100)

            val ok = TermuxBootstrap.downloadAndInstall(
                filesDir = filesDir,
                cacheDir = cacheDir,
                onProgress = { p ->
                    bootstrapProgress.value = p
                    updateNotif(
                        "Termux runtime: ${(p * 100).toInt()}%",
                        (p * 100).toInt(), 100
                    )
                },
                onLog = { addLog(it) }
            )
            bootstrapState.value = if (ok) InstallState.DONE else InstallState.FAILED
            if (!ok) {
                isInstalling.value = false
                updateNotif("Bootstrap ব্যর্থ — অ্যাপে ফিরে Retry দিন।", 0, 0)
                return
            }
            bootstrapProgress.value = 1f
        } else {
            bootstrapState.value = InstallState.DONE
            bootstrapProgress.value = 1f
        }

        // ── Step 2: selected runtimes ─────────────────────────────────────────
        val toInstall = Runtimes.all.filter { it.id in selectedIds }
        if (toInstall.isEmpty()) { finishInstall(); return }

        val installer = RuntimeInstaller(this)
        toInstall.forEachIndexed { idx, item ->
            setState(item.id, InstallState.INSTALLING)
            setProgress(item.id, 0f)
            updateNotif(
                "${item.name} ইনস্টল হচ্ছে… (${idx + 1}/${toInstall.size})",
                idx * 100 / toInstall.size, 100
            )

            val result = installer.install(
                item,
                onOutput = { line ->
                    addLog(line)
                    // Parse "45% [..." apt download lines for a live % in the notif
                    parseAptPct(line)?.let { p ->
                        setProgress(item.id, p)
                        updateNotif(
                            "${item.name}: ${(p * 100).toInt()}%  (${idx + 1}/${toInstall.size})",
                            (p * 100).toInt(), 100
                        )
                    }
                },
                onProgress = { p -> setProgress(item.id, p) }
            )

            when (result) {
                InstallResult.OK             -> { setState(item.id, InstallState.DONE); setProgress(item.id, 1f) }
                InstallResult.NO_PKG_MANAGER -> { pkgMissing.value = true; setState(item.id, InstallState.FAILED) }
                InstallResult.FAILED         -> setState(item.id, InstallState.FAILED)
            }
        }

        if (toInstall.all { states.value[it.id] == InstallState.DONE }) {
            finishInstall()
        } else {
            isInstalling.value = false
            updateNotif("কিছু ইনস্টল ব্যর্থ — অ্যাপে ফিরে Retry দিন।", 0, 0)
        }
    }

    private suspend fun finishInstall() {
        SettingsStore(this).setSetupDone(true)
        isDone.value     = true
        isInstalling.value = false
        updateNotif("সব ইনস্টল সম্পন্ন! ✓", 100, 100)
        delay(4_000)
        stopSelf()
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun updateNotif(text: String, progress: Int, max: Int) =
        nm.notify(NOTIF_ID, buildNotif(text, progress, max, ind = false))

    private fun buildNotif(text: String, progress: Int, max: Int, ind: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ayan Agent সেটআপ")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(max, progress, ind || max == 0)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "ইনস্টলেশন অগ্রগতি",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "কোন runtime ইনস্টল হচ্ছে এবং কতটুকু হলো" }
        )
    }

    // ── State helpers (always called on Main so no synchronisation needed) ───

    private fun addLog(l: String) {
        log.value = (log.value + l).takeLast(200)
    }

    private fun setState(id: String, s: InstallState) {
        states.value = states.value + (id to s)
    }

    private fun setProgress(id: String, p: Float) {
        progressF.value = progressF.value + (id to p)
    }

    /** apt prints "45% [2 python …]" — extract the leading percentage as 0..1. */
    private fun parseAptPct(line: String): Float? =
        Regex("""^(\d+)%""").find(line.trim())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { it / 100f }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Intent extra: ArrayList<String> of selected runtime IDs to install. */
        const val EXTRA_IDS   = "selected_runtime_ids"
        private const val CHANNEL_ID = "install_progress"
        const val NOTIF_ID    = 2
    }
}
