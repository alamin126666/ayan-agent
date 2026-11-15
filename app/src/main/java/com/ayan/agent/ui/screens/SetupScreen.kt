package com.ayan.agent.ui.screens

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.Checkbox
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
import com.ayan.agent.InstallService
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

    /** কোন রানটাইম ইনস্টল করা হবে — default: সব চেক করা */
    val selectedRuntimes = mutableStateMapOf<String, Boolean>()

    // ── Battery optimisation ──────────────────────────────────────────────────
    /** true = battery opt IS on (bad for background install); UI shows a prompt. */
    var batteryOptOn by mutableStateOf(false)
        private set

    fun refreshBatteryOpt() {
        val app = getApplication<Application>()
        val pm  = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOptOn = !pm.isIgnoringBatteryOptimizations(app.packageName)
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ── InstallService binding ────────────────────────────────────────────────
    /**
     * When the user starts an install we hand the work to InstallService (a
     * foreground service) so it survives the app being backgrounded. We bind
     * back to it here and mirror its StateFlows into the ViewModel's own
     * Compose state so the UI keeps updating while the app is in the foreground.
     */
    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val svc = (b as InstallService.LocalBinder).service()
            viewModelScope.launch { svc.bootstrapState.collect    { bootstrapState    = it  } }
            viewModelScope.launch { svc.bootstrapProgress.collect { bootstrapProgress = it  } }
            viewModelScope.launch { svc.states.collect   { map -> map.forEach { (k, v) -> states[k]   = v } } }
            viewModelScope.launch { svc.progressF.collect{ map -> map.forEach { (k, v) -> progress[k] = v } } }
            viewModelScope.launch { svc.log.collect      { lines ->
                log.apply { clear(); addAll(lines.takeLast(80)) }
            } }
            viewModelScope.launch { svc.isInstalling.collect { installing = it } }
            viewModelScope.launch { svc.isDone.collect       { if (it) _allDone.value = true } }
            viewModelScope.launch { svc.pkgMissing.collect   { pkgMissing = it } }
        }
        override fun onServiceDisconnected(n: ComponentName?) = Unit
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unbindService(svcConn) }
    }
    // ─────────────────────────────────────────────────────────────────────────

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

    /**
     * File-manager access ("All files access"), like real Termux setup
     * asks for. Without it the guest cannot see /sdcard and the agent
     * cannot work on the user's files. It does NOT gate the installs —
     * `pkg` works without it — so this is a step, not a blocker.
     */
    var storageGranted by mutableStateOf(false)
        private set

    private var installing by mutableStateOf(false)

    init {
        Runtimes.all.forEach {
            states[it.id] = InstallState.NOT_INSTALLED
            selectedRuntimes[it.id] = true  // default: সব সিলেক্ট করা
        }
        val app = getApplication<Application>()
        refreshStorageState()
        refreshBatteryOpt()
        if (TermuxBootstrap.isInstalled(app.filesDir)) {
            bootstrapState = InstallState.DONE
            bootstrapProgress = 1f
        }
    }

    fun refreshStorageState() {
        storageGranted = hasStorageAccess(getApplication())
    }

    /** একটা runtime-এর checkbox toggle করো (DONE হলে toggle হবে না) */
    fun toggleRuntime(id: String) {
        if (states[id] == InstallState.DONE) return
        selectedRuntimes[id] = !(selectedRuntimes[id] ?: true)
    }

    /** সব রানটাইম সিলেক্ট করো */
    fun selectAll() {
        Runtimes.all.forEach { if (states[it.id] != InstallState.DONE) selectedRuntimes[it.id] = true }
    }

    /** সব রানটাইম ডিসিলেক্ট করো */
    fun deselectAll() {
        Runtimes.all.forEach { if (states[it.id] != InstallState.DONE) selectedRuntimes[it.id] = false }
    }

    /** Intent for the system screen where the user grants all-files access. */
    fun openStorageSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    companion object {
        fun hasStorageAccess(context: Context): Boolean {
            // Guarded: isExternalStorageManager() throws on some
            // environments (e.g. Robolectric, where the underlying
            // storage state is absent) — treat that as "not granted".
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Starts InstallService (foreground) with the selected runtime IDs and
     * binds to it so the ViewModel mirrors its state. The service keeps
     * running even if the user goes home — the notification shows progress.
     */
    fun installAll() {
        if (installing) return
        val app = getApplication<Application>()
        val ids = Runtimes.all.filter { selectedRuntimes[it.id] != false }.map { it.id }
        startInstallService(app, ids)
    }

    /** Retry a single failed runtime (also via the service, so it's background-safe). */
    fun retry(item: RuntimeItem) {
        if (installing) return
        val app = getApplication<Application>()
        startInstallService(app, listOf(item.id))
    }

    /** Retry bootstrap (re-runs the full flow — bootstrap first, then whatever is selected). */
    fun retryBootstrap() {
        if (installing) return
        installAll()
    }

    private fun startInstallService(app: Application, ids: List<String>) {
        val intent = Intent(app, InstallService::class.java).apply {
            putStringArrayListExtra(InstallService.EXTRA_IDS, ArrayList(ids))
        }
        app.startForegroundService(intent)
        app.bindService(intent, svcConn, Context.BIND_AUTO_CREATE)
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
    val context = LocalContext.current

    // File-access flow (real Termux asks for this in setup): the settings
    // screen on Android 11+, a runtime permission below that.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshStorageState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshStorageState() }
    // Battery optimisation: re-check when the user returns from system settings.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshBatteryOpt() }

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
            text = "প্রথমে Termux runtime (~30 MB, একবার) ডাউনলোড হবে — WiFi ব্যবহার করুন। তারপর টিকমার্ক দেওয়া টুলগুলো ইনস্টল হবে।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Battery optimisation prompt ───────────────────────────────────────
        // Like real Termux, we ask the user to disable battery optimisation so
        // Android does not kill the install when they switch apps or the screen
        // turns off. The card disappears once optimisation is off.
        if (vm.batteryOptOn) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "⚠️ ব্যাটারি অপ্টিমাইজেশন চালু আছে",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "ইনস্টলের মাঝে অ্যাপ minimize করলে Android বন্ধ করে দিতে পারে। বন্ধ করে দিন।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                OutlinedButton(onClick = {
                    batteryLauncher.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }) {
                    Text("বন্ধ করুন")
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        // Select All / Deselect All
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${Runtimes.all.count { vm.selectedRuntimes[it.id] != false }}/${Runtimes.all.size} সিলেক্ট",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.selectAll() }) { Text("সব নিন") }
            TextButton(onClick = { vm.deselectAll() }) { Text("সব বাদ") }
        }
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
            // File-manager access, like real Termux setup asks for.
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "ফাইল অ্যাক্সেস", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (vm.storageGranted) {
                                "দেওয়া হয়েছে — Termux /sdcard ব্যবহার করতে পারবে"
                            } else {
                                "দেওয়া হয়নি — Termux-এর মতো ফাইল ম্যানেজার অ্যাক্সেস দিন"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vm.storageGranted) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        OutlinedButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                settingsLauncher.launch(vm.openStorageSettings(context))
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }) {
                            Text("অনুমতি দিন")
                        }
                    }
                }
            }
            // Termux runtime (bootstrap) installs first — mandatory, no checkbox.
            item {
                RuntimeRow(
                    item = RuntimeItem("bootstrap", "Termux runtime", "bootstrap", ""),
                    state = vm.bootstrapState,
                    progress = vm.bootstrapProgress,
                    showCheckbox = false,
                    onRetry = { vm.retryBootstrap() }
                )
            }
            items(Runtimes.all) { item ->
                RuntimeRow(
                    item = item,
                    state = vm.states[item.id] ?: InstallState.NOT_INSTALLED,
                    progress = vm.progress[item.id] ?: 0f,
                    selected = vm.selectedRuntimes[item.id] ?: true,
                    showCheckbox = true,
                    onToggle = { vm.toggleRuntime(item.id) },
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
        val selectedCount = Runtimes.all.count { vm.selectedRuntimes[it.id] != false }
        Button(
            onClick = { vm.installAll() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedCount > 0) "$selectedCount টি ইনস্টল করুন"
                else "Bootstrap ইনস্টল করুন (কিছু সিলেক্ট নেই)"
            )
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
    selected: Boolean = true,
    showCheckbox: Boolean = true,
    onToggle: () -> Unit = {},
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Checkbox — bootstrap-এর জন্য দেখাবো না (mandatory)
            if (showCheckbox) {
                Checkbox(
                    checked = selected || state == InstallState.DONE,
                    onCheckedChange = { onToggle() },
                    // DONE হলে lock — আনচেক করা যাবে না
                    enabled = state != InstallState.DONE && state != InstallState.INSTALLING,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${item.name} ${item.version}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when (state) {
                        InstallState.NOT_INSTALLED -> if (selected) "ইনস্টল হবে" else "বাদ দেওয়া হয়েছে"
                        InstallState.INSTALLING -> "ইনস্টল হচ্ছে…"
                        InstallState.DONE -> "ইনস্টল হয়েছে ✓"
                        InstallState.FAILED -> "ব্যর্থ হয়েছে — আবার চেষ্টা করুন"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        InstallState.DONE -> MaterialTheme.colorScheme.primary
                        InstallState.FAILED -> MaterialTheme.colorScheme.error
                        InstallState.NOT_INSTALLED -> if (selected)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
                InstallState.NOT_INSTALLED -> if (!showCheckbox || selected) Icon(
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
