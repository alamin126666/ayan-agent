package com.ayan.agent.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile
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
     * The proot executable to run. Returns null when the binary is not
     * shipped for this ABI.
     *
     * Sources, in order:
     * (1) The native library dir, used IN PLACE. With legacy packaging the
     * installer extracts libproot_exec.so there, and that is the one place
     * Android lets an app execute a file from.
     * (2) A copy in the app's files dir (from an earlier run, or extracted
     * from the APK zip below). NOTE: on Android 10+ the OS refuses to
     * execute files the app itself wrote into its data dir (EACCES,
     * error=13 — seen on Alamin's phone 2026-11-15), so this is only a
     * fallback for devices where (1) is unavailable.
     * (3) Straight out of the APK zip, for this device's ABI.
     */
    fun installedBinary(context: Context): File? {
        // (1) Native library dir — execute in place, never copy it out.
        val fromLibDir = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
        if (fromLibDir.exists() && fromLibDir.canExecute()) return fromLibDir
        // (2) A copy made earlier.
        val dest = File(context.filesDir, "proot")
        if (dest.exists() && dest.canExecute()) return dest
        // (3) Straight out of the APK zip, for this device's ABI.
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        if (extractFromApk(context.applicationInfo.sourceDir, abi, dest)) {
            return if (dest.canExecute()) dest else null
        }
        return null
    }

    private fun makeExecutable(file: File) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    /**
     * Extracts `lib/<abi>/libproot_exec.so` from an APK zip into [dest]
     * and marks it executable. False when the entry is missing or unreadable.
     */
    internal fun extractFromApk(apkPath: String, abi: String, dest: File): Boolean {
        return try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/$abi/libproot_exec.so") ?: return false
                if (dest.exists() && dest.length() == entry.size) {
                    makeExecutable(dest)
                    return dest.canExecute()
                }
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            makeExecutable(dest)
            dest.canExecute()
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(context: Context): Boolean =
        installedBinary(context) != null &&
            TermuxBootstrap.isInstalled(context.filesDir)

    /**
     * Everything the runtime needs on disk before a session starts: the
     * dirs proot binds (shm, tmp), the guest's own tmp + home + cache dirs
     * (proot-distro creates the cache dir for a termux-type guest), and the
     * guest's DNS config — Android has no /etc/resolv.conf, so without this
     * `pkg` cannot resolve hostnames. DNS comes from the active network,
     * falling back to public resolvers when there is none (e.g. in tests).
     */
    fun prepareGuestFiles(context: Context) {
        val filesDir = context.filesDir
        File(filesDir, "proot-shm").mkdirs()
        File(filesDir, "proot-tmp").mkdirs()
        File(filesDir, "usr/tmp").mkdirs()
        File(filesDir, "home").mkdirs()
        File(filesDir, "data/data/com.termux/files/cache").mkdirs()
        val resolv = File(filesDir, "usr/etc/resolv.conf")
        if (!resolv.exists() || resolv.length() == 0L) {
            runCatching {
                resolv.parentFile?.mkdirs()
                resolv.writeText(dnsServers(context).joinToString("\n") { "nameserver $it" } + "\n")
            }
        }
    }

    private fun dnsServers(context: Context): List<String> {
        val fallback = listOf("8.8.8.8", "1.1.1.1")
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return fallback
            val servers = manager.getLinkProperties(manager.activeNetwork)
                ?.dnsServers?.map { it.hostAddress }?.filterNotNull().orEmpty()
            if (servers.isEmpty()) fallback else servers
        } catch (e: Exception) {
            fallback
        }
    }

    /** Environment for the guest (used both for ProcessBuilder and TerminalSession). */
    fun guestEnv(filesDir: File): Map<String, String> {
        val env = mutableMapOf(
            "HOME" to "$GUEST_PREFIX/home",
            "PATH" to "$GUEST_PREFIX/usr/bin",
            "PREFIX" to "$GUEST_PREFIX/usr",
            "TMPDIR" to "$GUEST_PREFIX/usr/tmp",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            // proot itself (the host process) needs a temp dir for its
            // "glue" rootfs. TMPDIR above is a GUEST path that does not
            // exist on the host, so without this proot fails with
            // "can't create temporary directory" before any bind happens.
            "PROOT_TMP_DIR" to File(filesDir, "proot-tmp").absolutePath
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
     * the proot binary itself. [extraBinds] are extra host→guest binds,
     * e.g. the user-selected project folder → /mnt/project.
     */
    fun buildArgv(
        filesDir: File,
        prootBinary: File,
        command: String?,
        extraBinds: List<Pair<String, String>> = emptyList()
    ): List<String> {
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
        for ((host, guest) in extraBinds) argv.add("--bind=$host:$guest")
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
        onOutput: (String) -> Unit,
        extraBinds: List<Pair<String, String>> = emptyList()
    ): Int = withContext(Dispatchers.IO) {
        try {
            prepareGuestFiles(context)
            val proot = installedBinary(context)
                ?: run { onOutput("proot is not available on this device."); return@withContext 1 }
            val process = ProcessBuilder(
                buildArgv(context.filesDir, proot, command, extraBinds)
            )
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
        } catch (e: Exception) {
            // Never let a failed start crash the app — report it as a failed run.
            onOutput("proot error: ${e.message}")
            1
        }
    }

    /** Everything `TerminalSession` needs to start an interactive shell. */
    data class SessionConfig(
        val shellPath: String,
        val args: Array<String>,
        val env: Array<String>
    )

    /**
     * Config for an interactive login shell inside the guest, or null when
     * the runtime is not installed. [prootBinary] comes from
     * [installedBinary]; the TerminalSession's own cwd is the host home dir.
     */
    fun terminalSessionConfig(filesDir: File, prootBinary: File): SessionConfig? {
        if (!TermuxBootstrap.isInstalled(filesDir)) return null
        val argv = buildArgv(filesDir, prootBinary, command = null)
        val env = guestEnv(filesDir).map { (k, v) -> "$k=$v" }.toTypedArray()
        return SessionConfig(
            shellPath = argv[0],
            args = argv.drop(1).toTypedArray(),
            env = env
        )
    }
}
