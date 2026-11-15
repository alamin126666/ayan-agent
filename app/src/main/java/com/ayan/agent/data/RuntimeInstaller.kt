package com.ayan.agent.data

import android.content.Context
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
 * Why an install can fail. NO_PKG_MANAGER is the common case on a normal
 * phone: `pkg` is Termux's package manager and it simply does not exist
 * outside Termux, so `pkg install ...` can never succeed there. The Setup
 * screen must tell the user that plainly (and offer Skip) instead of
 * showing a generic "failed — retry" for every runtime.
 */
enum class InstallResult { OK, FAILED, NO_PKG_MANAGER }

/**
 * Runs the install command in the phone runtime shell and streams output.
 * When the in-app Termux runtime is ready (bootstrap + proot), the command
 * runs inside the guest via `login -c` — that is how `pkg install` actually
 * works on a normal phone. Otherwise it falls back to the host shell and
 * first checks whether the Termux `pkg` tool exists at all; if it does not,
 * returns NO_PKG_MANAGER without pretending to install anything.
 * Pass the app context so the proot path can be used; without it only the
 * host-shell path is available.
 */
class RuntimeInstaller(private val context: Context? = null) {

    suspend fun hasPkgManager(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ProcessBuilder("sh", "-c", "command -v pkg")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }

    suspend fun install(
        item: RuntimeItem,
        onOutput: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        // Real Termux runtime: run `pkg install` inside the proot guest
        // (AgentShell picks the guest when it is ready, host `sh` otherwise).
        val ctx = context
        if (ctx != null && ProotSession.isAvailable(ctx)) {
            onOutput("$ ${item.installCommand}  (Termux runtime)")
            val code = AgentShell.run(ctx, item.installCommand, onOutput = onOutput)
            onProgress(1f)
            return@withContext if (code == 0) InstallResult.OK else InstallResult.FAILED
        }
        if (!hasPkgManager()) {
            onOutput("এই ফোনে Termux নেই — `pkg` পাওয়া যায়নি, তাই ইনস্টল করা যাবে না।")
            return@withContext InstallResult.NO_PKG_MANAGER
        }
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
            if (code == 0) InstallResult.OK else InstallResult.FAILED
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
            InstallResult.FAILED
        }
    }
}
