package com.ayan.agent.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.data.InstallState
import com.ayan.agent.data.RuntimeInstaller
import com.ayan.agent.data.RuntimeItem
import com.ayan.agent.data.Runtimes
import com.ayan.agent.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val installer = RuntimeInstaller()

    val states = mutableStateMapOf<String, InstallState>()
    val progress = mutableStateMapOf<String, Float>()
    val log = mutableStateListOf<String>()

    private val _allDone = MutableStateFlow(false)
    val allDone = _allDone.asStateFlow()

    private var installing by mutableStateOf(false)

    init {
        Runtimes.all.forEach { states[it.id] = InstallState.NOT_INSTALLED }
    }

    fun installAll() {
        if (installing) return
        installing = true
        viewModelScope.launch {
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

    private suspend fun installOne(item: RuntimeItem) {
        states[item.id] = InstallState.INSTALLING
        progress[item.id] = 0f
        val ok = installer.install(
            item,
            onOutput = { log.add(it) },
            onProgress = { progress[item.id] = it }
        )
        states[item.id] = if (ok) InstallState.DONE else InstallState.FAILED
        if (ok) progress[item.id] = 1f
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
            text = "Ayan Agent চালাতে নিচের টুলগুলো ইনস্টল হচ্ছে।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Text(
                        text = vm.log.takeLast(6).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
