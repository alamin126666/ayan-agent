package com.ayan.agent.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one shared way anything in the app runs a shell command — the Setup
 * installer, the Terminal tab's fallback, and (next) the Chat agent's tool
 * calls. When the in-app Termux runtime is ready the command runs inside
 * the proot guest (so `python`, `php`, `node`, `java`, `pkg` all exist);
 * otherwise it runs in the host `sh` like before.
 *
 * [projectDir] is the user-selected folder the agent may work in. Inside
 * the guest it is bound to /mnt/project and the command starts there, so
 * the agent can only touch that folder. On the host fallback the command
 * runs with that directory as its working directory.
 *
 * A failed command never crashes the app — it reports the error and
 * returns a non-zero exit code.
 */
object AgentShell {

    const val GUEST_PROJECT_DIR = "/mnt/project"

    suspend fun run(
        context: Context,
        command: String,
        projectDir: File? = null,
        onOutput: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        try {
            runUnchecked(context, command, projectDir, onOutput)
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
            1
        }
    }

    private suspend fun runUnchecked(
        context: Context,
        command: String,
        projectDir: File?,
        onOutput: (String) -> Unit
    ): Int {
        if (ProotSession.isAvailable(context)) {
            val binds = projectDir?.let {
                listOf(it.absolutePath to GUEST_PROJECT_DIR)
            } ?: emptyList()
            val guestCommand = if (projectDir != null) {
                "cd $GUEST_PROJECT_DIR && $command"
            } else {
                command
            }
            return ProotSession.runCommand(context, guestCommand, onOutput, binds)
        }
        // Block-body function: the result needs an explicit return.
        return try {
            onOutput("$ $command  (host shell — Termux runtime not installed)")
            val process = ProcessBuilder("sh", "-c", command)
                .apply { projectDir?.let { directory(it) } }
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onOutput(it) }
            }
            process.waitFor()
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
            1
        }
    }
}
