package com.ayan.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ayan.agent.data.ProotSession
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the interactive Termux terminal session and keeps it alive as a
 * foreground service. Android 12+ kills an app's child processes (including
 * the proot guest) when the app is backgrounded unless they run under a
 * foreground service — that is what this is for. The Terminal tab binds to
 * it and attaches the session to its TerminalView; the session survives
 * leaving the tab and even leaving the app.
 */
class TermuxSessionService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): TermuxSessionService = this@TermuxSessionService
    }

    private val binder = LocalBinder()

    private var viewRef = WeakReference<TerminalView>(null)
    fun attachView(view: TerminalView?) {
        viewRef = WeakReference(view)
    }

    private val _sessionAlive = MutableStateFlow(false)
    val sessionAlive = _sessionAlive.asStateFlow()

    var session: TerminalSession? = null
        private set

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            viewRef.get()?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (session === finishedSession) session = null
            _sessionAlive.value = false
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            session?.write(text.toByteArray(), 0, text.toByteArray().size)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            viewRef.get()?.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int =
            TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    /** Creates the interactive login-shell session, or null if the runtime is not ready. */
    fun createSessionIfNeeded(): TerminalSession? {
        session?.let { return it }
        ProotSession.prepareGuestFiles(this)
        val proot = ProotSession.installedBinary(this) ?: return null
        val config = ProotSession.terminalSessionConfig(filesDir, proot) ?: return null
        val created = TerminalSession(
            config.shellPath,
            File(filesDir, "home").absolutePath,
            config.args,
            config.env,
            TRANSCRIPT_ROWS,
            sessionClient
        )
        session = created
        _sessionAlive.value = true
        return created
    }

    fun stopSession() {
        session?.finishIfRunning()
        session = null
        _sessionAlive.value = false
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        session = null
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminal session",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the Ayan Agent terminal running" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ayan Agent terminal")
            .setContentText("Termux terminal cholche")
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "termux_session"
        private const val NOTIFICATION_ID = 1
        private const val TRANSCRIPT_ROWS = 1000
    }
}
