package com.ayan.agent.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the Termux bootstrap under proot — Step 3 of the real Termux runtime.
 *
 * The stock bootstrap binaries have `/data/data/com.termux/files/usr`
 * hardcoded at compile time, so they cannot run directly under this app's
 * package name. proot (Termux's fork, GPL-2.0, built by tools/build-proot.sh
 * into jniLibs as libproot_exec.so) remaps paths with bind mounts, so the
 * guest sees exactly the paths it was built for. No root required.
 *
 * The command line follows proot-distro's canonical "termux-type" login
 * (proot-distro is GPL-3.0; only its argument construction is followed here,
 * no code is copied):
 *  - inner command is the bootstrap's own `usr/bin/login` (falls back to
 *    `usr/bin/bash --login` if login is missing from the bootstrap),
 *  - NO `-0` / `--change-id` and NO `--link2symlink` for a Termux guest —
 *    it runs as the app's real UID, like real Termux,
 *  - `--kill-on-exit` + `--sysvipc` (Termux-fork extensions),
 *  - `LD_PRELOAD` and `LD_LIBRARY_PATH` are removed from the guest env
 *    (Termux-exec's hook conflicts with proot's ptrace translation).
 */
object ProotSession {

    /** The hardcoded Termux prefix, as seen from inside the guest. */
    const val GUEST_PREFIX = "/data/data/com.termux/files"
    private const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"

    /** Android env vars proot-distro passes through to the guest, when set. */
    private val PASSTHROUGH_ENV = listOf(
        "ANDROID_ART_ROOT", "ANDROID_DATA", "ANDROID_I18N_ROOT",
        "ANDROID_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE"
    )

    /**
     * The proot executable, copied out of the APK's native libs into the
     * app's private files dir (where it can be executed) on first use.
     * Returns null when the binary is not shipped for this ABI.
     */
    fun installedBinary(context: Context): File? {
        val src = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
        if (!src.exists()) return null
        val dest = File(context.filesDir, "proot")
        if (!dest.exists() || dest.length() != src.length()) {
            src.copyTo(dest, overwrite = true)
            dest.setReadable(true, true)
            dest.setWritable(true, true)
            dest.setExecutable(true, true)
        }
        return if (dest.canExecute()) dest else null
    }

    fun isAvailable(context: Context): Boolean =
        installedBinary(context) != null &&
            TermuxBootstrap.isInstalled(context.filesDir)

    /** Environment for the guest (used both for ProcessBuilder and TerminalSession). */
    fun guestEnv(filesDir: File): Map<String, String> {
        val env = mutableMapOf(
            "HOME" to "$GUEST_PREFIX/home",
            "PATH" to "$GUEST_PREFIX/usr/bin",
            "PREFIX" to "$GUEST_PREFIX/usr",
            "TMPDIR" to "$GUEST_PREFIX/usr/tmp",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor"
        )
        for (key in PASSTHROUGH_ENV) {
            System.getenv(key)?.let { env[key] = it }
        }
        // Never leak host loader vars into the guest (see class doc).
        env.remove("LD_PRELOAD")
        env.remove("LD_LIBRARY_PATH")
        return env
    }

    /**
     * Full proot argv for one session. [command] runs via `login -c` when
     * given; null starts an interactive login shell. The first element is
     * the proot binary itself.
     */
    fun buildArgv(filesDir: File, prootBinary: File, command: String?): List<String> {
        val p = GUEST_PREFIX
        val argv = mutableListOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--sysvipc",
            "--kernel-release=$FAKE_KERNEL_RELEASE",
            "-L",
            "--rootfs=${filesDir.absolutePath}",
            "--cwd=$p/home",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=${File(filesDir, "proot-shm").absolutePath}:/dev/shm",
            "--bind=/data/app",
            "--bind=/data/dalvik-cache",
            "--bind=/data/misc/apexdata/com.android.art/dalvik-cache",
            "--bind=/sdcard",
            "--bind=/storage",
            "--bind=/apex",
            "--bind=/system",
            "--bind=/vendor",
            "--bind=${File(filesDir, "usr").absolutePath}:$p/usr",
            "--bind=${File(filesDir, "home").absolutePath}:$p/home"
        )
        val login = File(filesDir, "usr/bin/login")
        if (login.exists()) {
            argv.add("$p/usr/bin/login")
        } else {
            argv.add("$p/usr/bin/bash")
            argv.add("--login")
        }
        if (command != null) {
            argv.add("-c")
            argv.add(command)
        }
        return argv
    }

    /** One-shot command inside the guest; streams output, returns exit code. */
    suspend fun runCommand(
        context: Context,
        command: String,
        onOutput: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val proot = installedBinary(context)
            ?: run { onOutput("proot is not available on this device."); return@withContext 1 }
        File(context.filesDir, "proot-shm").mkdirs()
        val process = ProcessBuilder(buildArgv(context.filesDir, proot, command))
            .apply {
                environment().clear()
                environment().putAll(guestEnv(context.filesDir))
            }
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { onOutput(it) }
        }
        process.waitFor()
    }
}
