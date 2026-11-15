package com.ayan.agent.ui.screens

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.data.InstallResult
import com.ayan.agent.data.InstallState
import com.ayan.agent.data.RuntimeInstaller
import com.ayan.agent.data.RuntimeItem
import com.ayan.agent.data.Runtimes
import com.ayan.agent.data.SettingsStore
import com.ayan.agent.data.TermuxBootstrap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    // The app context lets the installer run `pkg install` inside the
    // proot'd Termux runtime when it is ready.
    private val installer = RuntimeInstaller(application)

    val states = mutableStateMapOf<String, InstallState>()
    val progress = mutableStateMapOf<String, Float>()
    val log = mutableStateListOf<String>()

    private val _allDone = MutableStateFlow(false)
    val allDone = _allDone.asStateFlow()

    /** True when this phone has no `pkg` (no Termux), so no runtime can install here. */
    var pkgMissing by mutableStateOf(false)
        private set

    /** The Termux bootstrap (mini Linux rootfs) must install before any `pkg` install can run. */
    var bootstrapState by mutableStateOf(InstallState.NOT_INSTALLED)
        private set
    var bootstrapProgress by mutableStateOf(0f)
        private set

    private var installing by mutableStateOf(false)

    init {
        Runtimes.all.forEach { states[it.id] = InstallState.NOT_INSTALLED }
        val app = getApplication<Application>()
        if (TermuxBootstrap.isInstalled(app.filesDir)) {
            bootstrapState = InstallState.DONE
            bootstrapProgress = 1f
        }
    }

    fun installAll() {
        if (installing) return
        installing = true
        viewModelScope.launch {
            // Step 1: Termux runtime (bootstrap). Without it there is no
            // `pkg` at all, so the runtime installs below cannot run.
            if (!ensureBootstrap()) {
                installing = false
                return@launch
            }
            for (item in Runtimes.all) {
                installOne(item)
            }
            installing = false
            if (Runtimes.all.all { states[it.id] == InstallState.DONE }) {
                store.setSetupDone(true)
                _allDone.value = true
            }
        }
    }

    fun retry(item: RuntimeItem) {
        if (installing) return
        viewModelScope.launch { installOne(item) }
    }

    fun retryBootstrap() {
        if (installing) return
        viewModelScope.launch { ensureBootstrap() }
    }

    /** Downloads + extracts the Termux bootstrap if it is not installed yet. */
    private suspend fun ensureBootstrap(): Boolean {
        val app = getApplication<Application>()
        if (TermuxBootstrap.isInstalled(app.filesDir)) {
            bootstrapState = InstallState.DONE
            bootstrapProgress = 1f
            return true
        }
        bootstrapState = InstallState.INSTALLING
        bootstrapProgress = 0f
        val ok = TermuxBootstrap.downloadAndInstall(
            filesDir = app.filesDir,
            cacheDir = app.cacheDir,
            // Callbacks arrive on a background thread; Compose state must
            // only be mutated on the Main thread.
            onProgress = { p -> viewModelScope.launch { bootstrapProgress = p } },
            onLog = { line -> viewModelScope.launch { log.add(line) } }
        )
        bootstrapState = if (ok) InstallState.DONE else InstallState.FAILED
        if (ok) bootstrapProgress = 1f
        return ok
    }

    /**
     * Skip setup: the Chat agent works fully without local runtimes (it
     * talks to the provider API over the internet). Only the Terminal's
     * local commands need Python/PHP/Java/Kotlin on the phone.
     */
    fun skipSetup() {
        if (installing) return
        viewModelScope.launch {
            store.setSetupDone(true)
            _allDone.value = true
        }
    }

    private suspend fun installOne(item: RuntimeItem) {
        states[item.id] = InstallState.INSTALLING
        progress[item.id] = 0f
        when (
            installer.install(
                item,
                // Callbacks arrive on a background thread; Compose state must
                // only be mutated on the Main thread.
                onOutput = { line -> viewModelScope.launch { log.add(line) } },
                onProgress = { p -> viewModelScope.launch { progress[item.id] = p } }
            )
        ) {
            InstallResult.OK -> {
                states[item.id] = InstallState.DONE
                progress[item.id] = 1f
            }
            InstallResult.NO_PKG_MANAGER -> {
                pkgMissing = true
                states[item.id] = InstallState.FAILED
            }
            InstallResult.FAILED -> states[item.id] = InstallState.FAILED
        }
    }
}

/** Step 2 — Setup page (PRD v0.3): install runtimes, progress + Retry/Resume. */
@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val vm: SetupViewModel = viewModel()
    val allDone by vm.allDone.collectAsState()

    if (allDone) {
        // Hand over to the Success screen once everything installed.
        androidx.compose.runtime.LaunchedEffect(Unit) { onSetupComplete() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "সেটআপ",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "প্রথমে Termux runtime (~30 MB, একবার) ডাউনলোড হবে — WiFi ব্যবহার করুন। তারপর নিচের টুলগুলো ইনস্টল হবে।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (vm.pkgMissing) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Termux runtime এখনো চালু হয়নি, তাই `pkg` দিয়ে টুল ইনস্টল হচ্ছে না। Bootstrap ইনস্টল হয়ে গেলে প্রত্যেকটার পাশের retry চাপো, বা নিচে \"এড়িয়ে যান\" চাপলে পরে করা যাবে।",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Termux runtime (bootstrap) installs first — the `pkg` installs below need it.
            item {
                RuntimeRow(
                    item = RuntimeItem("bootstrap", "Termux runtime", "bootstrap", ""),
                    state = vm.bootstrapState,
                    progress = vm.bootstrapProgress,
                    onRetry = { vm.retryBootstrap() }
                )
            }
            items(Runtimes.all) { item ->
                RuntimeRow(
                    item = item,
                    state = vm.states[item.id] ?: InstallState.NOT_INSTALLED,
                    progress = vm.progress[item.id] ?: 0f,
                    onRetry = { vm.retry(item) }
                )
            }
            item {
                if (vm.log.isNotEmpty()) {
                    LogCard(lines = vm.log)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { vm.installAll() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("সব ইনস্টল করুন")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.skipSetup() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("এড়িয়ে যান — পরে করবো")
        }
    }
}

/**
 * Terminal-style log card: shows the FULL install log (not just the last
 * few lines) and a "copy all" button, so the whole log can be pasted
 * somewhere for diagnosis. Auto-scrolls to the newest line.
 */
@Composable
private fun LogCard(lines: List<String>) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Follow the newest line as installs stream in.
    LaunchedEffect(lines.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "টার্মিনাল লগ (${lines.size} লাইন)",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Ayan Agent setup log", lines.joinToString("\n"))
                    )
                    copied = true
                    scope.launch {
                        delay(2000)
                        copied = false
                    }
                }
            ) {
                Text(
                    text = if (copied) "কপি হয়েছে ✓" else "সব কপি করুন",
                    color = Color(0xFFBB86FC)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFD0D0D0)
            )
        }
    }
}

@Composable
private fun RuntimeRow(
    item: RuntimeItem,
    state: InstallState,
    progress: Float,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${item.name} ${item.version}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when (state) {
                        InstallState.NOT_INSTALLED -> "ইনস্টল হয়নি"
                        InstallState.INSTALLING -> "ইনস্টল হচ্ছে…"
                        InstallState.DONE -> "ইনস্টল হয়েছে"
                        InstallState.FAILED -> "ব্যর্থ হয়েছে — আবার চেষ্টা করুন"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (state) {
                InstallState.DONE -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                InstallState.FAILED -> IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                }
                InstallState.INSTALLING -> CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    strokeWidth = 2.dp
                )
                InstallState.NOT_INSTALLED -> Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state == InstallState.INSTALLING || state == InstallState.DONE) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
