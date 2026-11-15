package com.ayan.agent.ui.screens

import android.app.Application
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.data.AgentShell
import com.ayan.agent.data.InstallResult
import com.ayan.agent.data.InstallState
import com.ayan.agent.data.RuntimeInstaller
import com.ayan.agent.data.RuntimeItem
import com.ayan.agent.data.Runtimes
import com.ayan.agent.data.SettingsStore
import com.ayan.agent.data.provider.BuiltInProviders
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val gson = Gson()

    // collectAsState() is a @Composable call, so it cannot run in a ViewModel.
    // The ViewModel exposes the raw DataStore Flows; the screen collects them.
    val providerId: Flow<String> = store.providerId
    val modelId: Flow<String> = store.modelId
    val fallbackOn: Flow<Boolean> = store.multiKeyFallback

    var currentProviderId: String = "openrouter"
        private set

    var keys = mutableStateListOf<String>()
        private set

    // ── Runtime Management ────────────────────────────────────────────────────
    /** প্রতিটা runtime-এর বর্তমান install অবস্থা */
    val runtimeStates = mutableStateMapOf<String, InstallState>()
    /** Install log লাইনগুলো */
    val runtimeLog = mutableStateListOf<String>()
    private var runtimeInstalling by mutableStateOf(false)

    /** Settings থেকে যেকোনো runtime install/reinstall করো */
    fun installRuntime(item: RuntimeItem) {
        if (runtimeInstalling) return
        runtimeInstalling = true
        runtimeStates[item.id] = InstallState.INSTALLING
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val installer = RuntimeInstaller(ctx)
            val result = installer.install(
                item,
                onOutput = { line -> viewModelScope.launch { runtimeLog.add(line) } },
                onProgress = {}
            )
            runtimeStates[item.id] = when (result) {
                InstallResult.OK -> InstallState.DONE
                InstallResult.NO_PKG_MANAGER -> {
                    runtimeLog.add("Termux runtime install করুন Setup থেকে, তারপর আবার চেষ্টা করুন।")
                    InstallState.FAILED
                }
                InstallResult.FAILED -> InstallState.FAILED
            }
            runtimeInstalling = false
        }
    }

    fun clearRuntimeLog() { runtimeLog.clear() }
    // ─────────────────────────────────────────────────────────────────────────

    fun loadKeys(allKeys: Map<String, List<String>>, provider: String) {
        currentProviderId = provider
        keys.clear()
        keys.addAll(allKeys[provider].orEmpty())
    }

    fun selectProvider(id: String) {
        viewModelScope.launch { store.setProviderId(id) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { store.setModelId(model) }
    }

    fun setFallback(enabled: Boolean) {
        viewModelScope.launch { store.setMultiKeyFallback(enabled) }
    }

    fun addKey(key: String, onSaved: (Map<String, List<String>>) -> Unit) {
        if (key.isBlank()) return
        keys.add(key.trim())
        persist(onSaved)
    }

    fun removeKey(key: String, onSaved: (Map<String, List<String>>) -> Unit) {
        keys.remove(key)
        persist(onSaved)
    }

    private fun persist(onSaved: (Map<String, List<String>>) -> Unit) {
        viewModelScope.launch {
            val json = store.getString(com.ayan.agent.data.PrefKeys.API_KEYS_JSON, "{}")
            val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
            val map: MutableMap<String, MutableList<String>> =
                runCatching { gson.fromJson<MutableMap<String, MutableList<String>>>(json, type) }
                    .getOrDefault(mutableMapOf())
            map[currentProviderId] = keys.toMutableList()
            val out = gson.toJson(map)
            store.setApiKeysJson(out)
            val outType = object : TypeToken<Map<String, List<String>>>() {}.type
            onSaved(gson.fromJson(out, outType))
        }
    }
}

/** Step 6 — Settings (PRD v0.3): providers, custom provider, models, multi-key fallback. */
@OptIn(ExperimentalMaterial3Api::class) // Card(onClick = ...) is experimental
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val providerId by vm.providerId.collectAsState(initial = "openrouter")
    val model by vm.modelId.collectAsState(initial = "")
    val fallbackOn by vm.fallbackOn.collectAsState(initial = true)

    var allKeys by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var newKey by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var customProviders by remember { mutableStateOf(listOf<com.ayan.agent.data.provider.ProviderConfig>()) }

    // Load saved keys once the provider changes.
    androidx.compose.runtime.LaunchedEffect(providerId) {
        val store = SettingsStore(context)
        val json = store.getString(com.ayan.agent.data.PrefKeys.API_KEYS_JSON, "{}")
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        val map: Map<String, List<String>> =
            runCatching { Gson().fromJson<Map<String, List<String>>>(json, type) }.getOrDefault(emptyMap())
        allKeys = map
        vm.loadKeys(map, providerId)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text(
                "প্রোভাইডার, মডেল আর API key — সবাই নিজের key দিয়ে ফ্রিতে ব্যবহার করতে পারবে।",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            (BuiltInProviders.list + customProviders).forEach { p ->
                Card(
                    onClick = { vm.selectProvider(p.id) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (p.id == providerId) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            Text("Custom provider যোগ করুন", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = customName, onValueChange = { customName = it },
                label = { Text("নাম (যেমন: My Provider)") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customUrl, onValueChange = { customUrl = it },
                label = { Text("Base URL (OpenAI-compatible)") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customModel, onValueChange = { customModel = it },
                label = { Text("Default model") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                if (customName.isNotBlank() && customUrl.isNotBlank()) {
                    customProviders = customProviders + com.ayan.agent.data.provider.ProviderConfig(
                        id = "custom_${customName.lowercase().replace(" ", "_")}",
                        name = customName, baseUrl = customUrl,
                        defaultModel = customModel.ifBlank { "default" }
                    )
                    customName = ""; customUrl = ""; customModel = ""
                }
            }) { Text("Add custom provider") }
        }

        item {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = model, onValueChange = { vm.setModel(it) },
                label = { Text("Model (খালি রাখলে provider-এর default)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Multi-key fallback", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "লিমিট লাগলে অটো পরের key-তে যাবে, সেশন/মেমোরি সেভ থাকবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = fallbackOn, onCheckedChange = { vm.setFallback(it) })
            }
        }

        item {
            Text("API keys — $providerId (${vm.keys.size}টি)", style = MaterialTheme.typography.titleMedium)
            Text(
                "এক প্রোভাইডারের অনেকগুলো key রাখা যাবে (অন্য অ্যাকাউন্টের)।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(vm.keys) { key ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = key.take(6) + "••••" + key.takeLast(4),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(onClick = { vm.removeKey(key) { allKeys = it } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove key")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKey, onValueChange = { newKey = it },
                    label = { Text("নতুন API key") }, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    vm.addKey(newKey) { allKeys = it }
                    newKey = ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add key")
                }
            }
        }

        // ── Runtime Management ────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Runtime Management", style = MaterialTheme.typography.titleLarge)
            Text(
                "Setup-এর পরেও এখান থেকে যেকোনো runtime install বা reinstall করা যাবে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }
        items(Runtimes.all) { runtime ->
            val rState = vm.runtimeStates[runtime.id] ?: InstallState.NOT_INSTALLED
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${runtime.name} ${runtime.version}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when (rState) {
                            InstallState.DONE -> "ইনস্টল হয়েছে ✓"
                            InstallState.INSTALLING -> "ইনস্টল হচ্ছে…"
                            InstallState.FAILED -> "ব্যর্থ — আবার চেষ্টা করুন"
                            InstallState.NOT_INSTALLED -> "ইনস্টল করা হয়নি"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (rState) {
                            InstallState.DONE -> MaterialTheme.colorScheme.primary
                            InstallState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                when (rState) {
                    InstallState.DONE -> IconButton(onClick = { vm.installRuntime(runtime) }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reinstall",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    InstallState.INSTALLING -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .padding(4.dp),
                        strokeWidth = 2.dp
                    )
                    else -> IconButton(onClick = { vm.installRuntime(runtime) }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Install",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        // Runtime install log (mini terminal)
        if (vm.runtimeLog.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Runtime log (${vm.runtimeLog.size} লাইন)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { vm.clearRuntimeLog() }
                        ) { Text("Clear", color = Color(0xFFBB86FC)) }
                    }
                    // শেষ ১০ লাইন দেখাও
                    vm.runtimeLog.takeLast(10).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFD0D0D0),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────
    }
}
