package com.ayan.agent.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
