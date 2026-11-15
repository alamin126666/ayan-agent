package com.ayan.agent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RuntimeItem(
    val id: String,
    val name: String,
    val version: String,
    val installCommand: String
)

/** Runtimes installed from the Setup page (PRD v0.3), via the Termux-like runtime. */
object Runtimes {
    val all = listOf(
        RuntimeItem("python", "Python", "3.11", "pkg install -y python"),
        RuntimeItem("php", "PHP", "8.3", "pkg install -y php"),
        RuntimeItem("web", "HTML / CSS + Node", "20", "pkg install -y nodejs"),
        RuntimeItem("java", "Java (JDK)", "17", "pkg install -y openjdk-17"),
        RuntimeItem("kotlin", "Kotlin", "1.9", "pkg install -y kotlin")
    )
}

enum class InstallState { NOT_INSTALLED, INSTALLING, DONE, FAILED }

/**
 * Runs the install command in the phone runtime shell and streams output.
 * Returns true only when the command exits with code 0.
 * If the Termux `pkg` tool is missing the install fails with a clear error
 * and the Setup screen offers Retry.
 */
class RuntimeInstaller {

    suspend fun install(
        item: RuntimeItem,
        onOutput: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onOutput("$ ${item.installCommand}")
            val process = ProcessBuilder("sh", "-c", item.installCommand)
                .redirectErrorStream(true)
                .start()
            var lines = 0
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    lines++
                    onOutput(line)
                    // Progress is approximate: driven by output volume.
                    onProgress((lines % 100) / 100f)
                }
            }
            val code = process.waitFor()
            onProgress(1f)
            code == 0
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
            false
        }
    }
}
