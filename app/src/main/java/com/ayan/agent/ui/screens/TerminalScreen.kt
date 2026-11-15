package com.ayan.agent.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.TermuxSessionService
import com.ayan.agent.data.ProotSession
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    val lines = mutableStateListOf<String>()
    var workDir by mutableStateOf(application.filesDir.absolutePath)
    var running by mutableStateOf(false)

    init {
        lines.add("Ayan Agent Terminal — Termux-এর মতো রানটাইম টার্মিনাল")
        lines.add("work dir: $workDir")
        lines.add("সরাসরি টাইপ করুন, Enter দিলে কমান্ড চলবে।")
    }

    /** Run a command in the real phone shell, streaming output live. */
    fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || running) return
        lines.add("$ $cmd")
        if (cmd.startsWith("cd ")) {
            val target = cmd.removePrefix("cd ").trim()
            val next = if (target.startsWith("/")) File(target) else File(workDir, target)
            if (next.isDirectory) {
                workDir = next.absolutePath
                lines.add("work dir: $workDir")
            } else {
                lines.add("cd: no such directory: $target")
            }
            return
        }
        if (cmd == "clear") {
            lines.clear()
            return
        }
        running = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("sh", "-c", cmd)
                        .directory(File(workDir))
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().useLines { seq ->
                        seq.forEach { line ->
                            withContext(Dispatchers.Main) { lines.add(line) }
                        }
                    }
                    val code = process.waitFor()
                    withContext(Dispatchers.Main) {
                        lines.add("[exit $code]")
                        running = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        lines.add("Error: ${e.message}")
                        running = false
                    }
                }
            }
        }
    }
}

/** Step 5 — Real runtime terminal (PRD v0.3): direct typing at the prompt. */
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var runtimeReady by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        runtimeReady = withContext(Dispatchers.IO) { ProotSession.isAvailable(context) }
    }

    when (runtimeReady) {
        null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        true -> RealTerminalScreen(modifier)
        false -> FallbackTerminalScreen(modifier)
    }
}

/**
 * The real Termux terminal: Termux's own TerminalView showing a PTY session
 * that runs the bootstrap's login shell under proot. The session lives in
 * TermuxSessionService (foreground service) so it survives leaving the tab.
 */
@Composable
private fun RealTerminalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var service by remember { mutableStateOf<TermuxSessionService?>(null) }
    var alive by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TermuxSessionService::class.java)
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as TermuxSessionService.LocalBinder).service()
                service = svc
                svc.createSessionIfNeeded()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        context.bindService(
            Intent(context, TermuxSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            service?.attachView(null)
            context.unbindService(connection)
        }
    }

    LaunchedEffect(service) {
        service?.sessionAlive?.collect { alive = it }
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float) = scale
            override fun onSingleTapUp(e: MotionEvent) = Unit
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = true
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
            override fun onLongPress(event: MotionEvent) = false
            override fun readControlKey() = false
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
            override fun onEmulatorSet() = Unit
            override fun logError(tag: String, message: String) { Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                Log.e(tag, message, e)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx).apply {
                    setTerminalViewClient(viewClient)
                    setTextSize(16)
                    isFocusableInTouchMode = true
                    requestFocus()
                }
            },
            update = { view ->
                val s = service?.session
                if (s != null && view.mTermSession !== s) {
                    view.attachSession(s)
                    service?.attachView(view)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (service != null && !alive) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Session shesh hoye geche",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = { service?.createSessionIfNeeded() }) {
                    Text("Notun session")
                }
            }
        }
    }
}

/** Shown when the Termux runtime is not installed yet: the old host-shell terminal. */
@Composable
private fun FallbackTerminalScreen(modifier: Modifier = Modifier) {
    val vm: TerminalViewModel = viewModel()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.lines.size) {
        if (vm.lines.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(vm.lines.size - 1) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(12.dp)
    ) {
        Text(
            text = "Termux runtime install hoyni — Setup theke install koro. Ekhon host shell cholche.",
            color = Color(0xFFF59E0B),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(vm.lines) { line ->
                Text(
                    text = line,
                    color = Color(0xFF22C55E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$ ",
                color = Color(0xFF22C55E),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        vm.runCommand(input)
                        input = ""
                    }
                )
            )
        }
        Text(
            text = "dir: ${vm.workDir}",
            color = Color(0xFF6B7280),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
