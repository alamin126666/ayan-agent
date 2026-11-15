package com.ayan.agent.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayan.agent.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorFile(val uri: Uri, val name: String, val path: String)
data class SearchHit(val file: EditorFile, val lineNo: Int, val line: String)
data class UndoEntry(val uri: Uri, val oldText: String)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    val files = mutableStateListOf<EditorFile>()
    val openTabs = mutableStateListOf<EditorFile>()
    val searchHits = mutableStateListOf<SearchHit>()
    val recentFiles = mutableStateListOf<EditorFile>()
    private val undoStack = ArrayDeque<UndoEntry>()
    var currentFile by mutableStateOf<EditorFile?>(null)
    var currentText by mutableStateOf("")
    var status by mutableStateOf("")
    var gitStatus by mutableStateOf("Git: not a repo")

    /** Load the file tree of the selected work folder (SAF tree URI). */
    fun loadTree() {
        viewModelScope.launch {
            val uriStr = SettingsStore(context).workFolderUri.first() ?: return@launch
            val root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return@launch
            val found = mutableListOf<EditorFile>()
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    if (child.isDirectory) {
                        walk(child, "$prefix${child.name}/")
                    } else if (child.isFile) {
                        found.add(EditorFile(child.uri, child.name ?: "file", prefix + (child.name ?: "")))
                    }
                }
            }
            withContext(Dispatchers.IO) { walk(root, "") }
            files.clear()
            files.addAll(found.sortedBy { it.path })
            gitStatus = if (found.any { it.name == ".git" || it.path.contains(".git/") }) {
                "Git: repo detected"
            } else {
                "Git: not a repo"
            }
            status = "${found.size}টি ফাইল পাওয়া গেছে"
        }
    }

    fun openFile(file: EditorFile) {
        viewModelScope.launch {
            val text = readText(file.uri)
            currentFile = file
            currentText = text
            if (!openTabs.contains(file)) openTabs.add(file)
            recentFiles.remove(file)
            recentFiles.add(0, file)
        }
    }

    fun closeTab(file: EditorFile) {
        openTabs.remove(file)
        if (currentFile == file) {
            currentFile = openTabs.lastOrNull()
            currentFile?.let { openFile(it) } ?: run { currentText = "" }
        }
    }

    fun saveCurrent() {
        val file = currentFile ?: return
        viewModelScope.launch {
            undoStack.addLast(UndoEntry(file.uri, readText(file.uri)))
            writeText(file.uri, currentText)
            status = "${file.name} সেভ হয়েছে"
        }
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            writeText(entry.uri, entry.oldText)
            val file = files.find { it.uri == entry.uri }
            if (file != null && currentFile == file) currentText = entry.oldText
            status = "Undo করা হয়েছে"
        }
    }

    /** Search by file name. */
    fun searchByName(query: String): List<EditorFile> =
        if (query.isBlank()) files else files.filter { it.name.contains(query, ignoreCase = true) }

    /** Search any text across all files, showing every file that contains it. */
    fun searchText(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            searchHits.clear()
            for (file in files) {
                val text = readText(file.uri)
                text.lines().forEachIndexed { i, line ->
                    if (line.contains(query, ignoreCase = true)) {
                        searchHits.add(SearchHit(file, i + 1, line.trim()))
                    }
                }
            }
            status = "${searchHits.size}টি মিল পাওয়া গেছে"
        }
    }

    /** Replace across all files from one place, with undo support. */
    fun replaceAll(find: String, replace: String) {
        if (find.isBlank()) return
        viewModelScope.launch {
            var changedFiles = 0
            for (file in files) {
                val text = readText(file.uri)
                if (text.contains(find)) {
                    undoStack.addLast(UndoEntry(file.uri, text))
                    writeText(file.uri, text.replace(find, replace))
                    changedFiles++
                }
            }
            status = "$changedFilesটি ফাইলে বদলানো হয়েছে"
            loadTree()
        }
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText().orEmpty() }
        }.getOrDefault("")
    }

    private suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter().use {
                it?.write(text)
            }
        }
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "if", "else", "for", "while",
    "return", "import", "package", "private", "public", "suspend", "override",
    "data", "sealed", "enum", "interface", "companion", "init", "this", "it",
    "true", "false", "null", "try", "catch", "finally", "when", "is", "in"
)

/** Very small syntax highlighter: keywords, strings, comments. */
@Composable
fun highlightedCode(text: String): androidx.compose.ui.text.AnnotatedString {
    return remember(text) {
        buildAnnotatedString {
            var i = 0
            val keywordColor = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF7C3AED))
            val stringColor = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF16A34A))
            val commentColor = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF9CA3AF))
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                        withStyle(commentColor) { append(text.substring(i, end)) }
                        i = end
                    }
                    text[i] == '"' -> {
                        val end = text.indexOf('"', i + 1).let { if (it == -1) text.length else it + 1 }
                        withStyle(stringColor) { append(text.substring(i, end)) }
                        i = end
                    }
                    text[i].isLetter() -> {
                        var j = i
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        val word = text.substring(i, j)
                        if (word in CODE_KEYWORDS) withStyle(keywordColor) { append(word) }
                        else append(word)
                        i = j
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}

/** Step 7 — VS Code-like code editor (PRD v0.3). Lag-free, smooth. */
@Composable
fun CodeEditorScreen(modifier: Modifier = Modifier) {
    val vm: EditorViewModel = viewModel()
    var nameQuery by remember { mutableStateOf("") }
    var textQuery by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadTree() }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = nameQuery,
                onValueChange = { nameQuery = it },
                label = { Text("ফাইলের নাম খুঁজুন") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Default.Search, contentDescription = "Search text")
            }
            IconButton(onClick = { vm.undo() }) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
        }

        if (showSearch) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textQuery,
                    onValueChange = { textQuery = it },
                    label = { Text("টেক্সট খুঁজুন") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = replaceWith,
                    onValueChange = { replaceWith = it },
                    label = { Text("বদলে লিখুন") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { vm.searchText(textQuery) }) { Text("Search") }
                Button(onClick = { vm.replaceAll(textQuery, replaceWith) }) { Text("Replace all") }
            }
            if (vm.searchHits.isNotEmpty()) {
                LazyColumn(modifier = Modifier.height(140.dp)) {
                    items(vm.searchHits) { hit ->
                        Text(
                            text = "${hit.file.path}:${hit.lineNo} — ${hit.line}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.openFile(hit.file) }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(vm.gitStatus, style = MaterialTheme.typography.bodySmall)
        Text(vm.status, style = MaterialTheme.typography.bodySmall)

        // Tabs
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.openTabs.forEach { tab ->
                Row(
                    modifier = Modifier
                        .clickable { vm.openFile(tab) }
                        .padding(6.dp)
                ) {
                    Text(tab.name, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { vm.closeTab(tab) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            }
        }

        // File tree (filtered by name search) + editor side by side in one column
        // for phones: tree on top, editor below.
        LazyColumn(modifier = Modifier.height(150.dp)) {
            items(vm.searchByName(nameQuery)) { file ->
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openFile(file) }
                        .padding(4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        vm.currentFile?.let { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(file.name, style = MaterialTheme.typography.titleMedium)
                Row {
                    Button(onClick = {
                        // Simple format: trim trailing spaces per line.
                        vm.currentText = vm.currentText.lines().joinToString("\n") { it.trimEnd() }
                    }) { Text("Format") }
                    Spacer(modifier = Modifier.padding(4.dp))
                    Button(onClick = { vm.saveCurrent() }) { Text("Save") }
                }
            }
            // Editor with line numbers + syntax highlight preview below the input.
            Row(modifier = Modifier.weight(1f)) {
                val lineCount = vm.currentText.lines().size
                LazyColumn(modifier = Modifier.padding(end = 8.dp)) {
                    items(lineCount) { n ->
                        Text(
                            text = "${n + 1}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
                BasicTextField(
                    value = vm.currentText,
                    onValueChange = { vm.currentText = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            }
            Text(
                text = "Syntax highlight: keywords বেগুনি, string সবুজ, comment ধূসর (preview-এ)।",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = highlightedCode(vm.currentText.take(300)),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        } ?: Text("কোনো ফাইল খোলা হয়নি — ওপরের তালিকা থেকে ফাইল বেছে নিন।")
    }
}
