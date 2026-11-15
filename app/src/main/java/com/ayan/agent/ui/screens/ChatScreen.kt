package com.ayan.agent.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.data.SettingsStore
import com.ayan.agent.data.provider.AnthropicClient
import com.ayan.agent.data.provider.ApiKeyPool
import com.ayan.agent.data.provider.BuiltInProviders
import com.ayan.agent.data.provider.ChatMessage
import com.ayan.agent.data.provider.OpenAiCompatibleClient
import com.ayan.agent.data.provider.isLimitError
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

enum class FeedKind { THINKING, COMMAND, CODE, FILE, ANSWER, SYSTEM }

data class FeedItem(val kind: FeedKind, val text: String)

data class ChatSession(val id: Long, val title: String)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val gson = Gson()

    val sessions = mutableStateListOf<ChatSession>()
    val feed = mutableStateListOf<FeedItem>()
    var currentSessionId by mutableStateOf<Long?>(null)
    var prdText by mutableStateOf("")
    var folderUri by mutableStateOf<Uri?>(null)
    var busy by mutableStateOf(false)

    private var sessionCounter = 0L

    fun newSession() {
        sessionCounter++
        val session = ChatSession(sessionCounter, "Session $sessionCounter")
        sessions.add(0, session)
        currentSessionId = session.id
        feed.clear()
        feed.add(FeedItem(FeedKind.SYSTEM, "নতুন সেশন খোলা হয়েছে। ফোল্ডার সিলেক্ট করে PRD লিখে প্রম্পট দিন।"))
    }

    fun setFolder(uri: Uri) {
        folderUri = uri
        viewModelScope.launch { store.setWorkFolderUri(uri.toString()) }
        feed.add(FeedItem(FeedKind.SYSTEM, "ফোল্ডার সিলেক্ট হয়েছে: $uri — সব কাজ শুধু এই ফোল্ডারেই হবে।"))
    }

    /** Send a prompt: plan → think → call provider (with multi-key fallback) → answer. */
    fun sendPrompt(prompt: String) {
        if (busy || prompt.isBlank()) return
        busy = true
        feed.add(FeedItem(FeedKind.THINKING, "ভাবছি: “$prompt”"))
        feed.add(FeedItem(FeedKind.COMMAND, "পরিকল্পনা তৈরি হচ্ছে…"))
        viewModelScope.launch {
            try {
                val providerId = store.getString(com.ayan.agent.data.PrefKeys.PROVIDER_ID, "openrouter")
                val keysJson = store.getString(com.ayan.agent.data.PrefKeys.API_KEYS_JSON, "{}")
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val allKeys: Map<String, List<String>> =
                    runCatching { gson.fromJson<Map<String, List<String>>>(keysJson, type) }.getOrDefault(emptyMap())
                val keys = allKeys[providerId].orEmpty()
                val pool = ApiKeyPool(keys)
                if (!pool.hasKeys()) {
                    feed.add(FeedItem(FeedKind.SYSTEM, "Settings-এ ${providerId}-এর API key যোগ করুন, তারপর আবার চেষ্টা করুন।"))
                    busy = false
                    return@launch
                }
                val config = BuiltInProviders.byId(providerId)
                val model = store.getString(com.ayan.agent.data.PrefKeys.MODEL_ID, "")
                    .ifBlank { config?.defaultModel ?: "gpt-4o-mini" }
                val messages = listOf(
                    ChatMessage("system", "You are Ayan Agent, a coding agent on Android. Plan first, then act. PRD:\n$prdText"),
                    ChatMessage("user", prompt)
                )
                var answer: String? = null
                var lastError: Exception? = null
                while (answer == null) {
                    val key = pool.current() ?: break
                    try {
                        answer = if (config?.needsAnthropicApi == true) {
                            AnthropicClient().chat(key, model, messages)
                        } else {
                            OpenAiCompatibleClient(config?.baseUrl ?: "").chat(key, model, messages)
                        }
                    } catch (e: Exception) {
                        lastError = e
                        val fallbackOn = true // multi-key fallback is on by default (PRD v0.3)
                        if (fallbackOn && isLimitError(e) && pool.rotate()) {
                            feed.add(FeedItem(FeedKind.SYSTEM, "লিমিট লেগেছে — পরের API key-তে যাওয়া হচ্ছে। সেশন/মেমোরি সেভ আছে।"))
                        } else break
                    }
                }
                if (answer != null) {
                    feed.add(FeedItem(FeedKind.ANSWER, answer))
                } else {
                    feed.add(FeedItem(FeedKind.SYSTEM, "উত্তর পাওয়া যায়নি: ${lastError?.message}"))
                }
            } finally {
                busy = false
            }
        }
    }
}

/** Step 4 — Chat (PRD v0.3): new session, folder pick, PRD editor, live feed. */
@Composable
fun ChatScreen(modifier: Modifier = Modifier, onOpenEditor: () -> Unit) {
    val vm: ChatViewModel = viewModel()
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setFolder(uri)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { vm.newSession() }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("New Session")
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text("Folder")
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenEditor) {
                Icon(Icons.Default.Code, contentDescription = "Code editor")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = vm.prdText,
            onValueChange = { vm.prdText = it },
            label = { Text("Full PRD editor — এখানে PRD লিখুন") },
            modifier = Modifier.fillMaxWidth().height(110.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.feed) { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (item.kind) {
                            FeedKind.THINKING -> MaterialTheme.colorScheme.primaryContainer
                            FeedKind.ANSWER -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("প্রম্পট লিখুন…") },
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    vm.sendPrompt(prompt)
                    prompt = ""
                },
                enabled = !vm.busy
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
        if (vm.folderUri == null) {
            Text(
                text = "ফোল্ডার সিলেক্ট করুন — এজেন্ট শুধু সেই ফোল্ডারেই কাজ করবে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
