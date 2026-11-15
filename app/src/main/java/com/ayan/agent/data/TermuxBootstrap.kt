package com.ayan.agent.data

import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Termux bootstrap (mini Linux rootfs with apt/pkg) — Kotlin port of the
 * extraction logic in Termux's own `TermuxInstaller` (termux-app, Apache 2.0).
 *
 * The bootstrap zip (~30 MB per CPU arch) comes from the termux-packages
 * releases and is verified by SHA-256 before extraction. It is installed
 * into the app's private files dir as `data/data/com.termux/files/usr`
 * (the real Termux $PREFIX path, as a real subtree of the rootfs) —
 * exactly proot-distro's "termux-type" layout. That matters: binding the
 * prefix in from elsewhere broke proot's interpreter lookup, so the
 * prefix now lives where the guest expects it, with no bind at all.
 *
 * NOTE: the stock bootstrap binaries have `/data/data/com.termux/files/usr`
 * hardcoded at compile time, so they cannot run directly under this app's
 * package name — that is what the proot step (Step 3) is for. This class
 * only downloads and extracts the rootfs; running it comes next.
 */
object TermuxBootstrap {

    /** Bootstrap release published by termux-packages (apt-android-7). */
    const val BOOTSTRAP_VERSION = "2026.02.12-r1+apt.android-7"
    private const val BOOTSTRAP_VERSION_URL = "2026.02.12-r1%2Bapt.android-7"

    /** SHA-256 of each arch's bootstrap zip (from termux-app's build.gradle). */
    val CHECKSUMS = mapOf(
        "aarch64" to "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
        "arm" to "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
        "i686" to "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
        "x86_64" to "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
    )

    fun downloadUrl(arch: String): String =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-" +
            "$BOOTSTRAP_VERSION_URL/bootstrap-$arch.zip"

    /** Maps an Android ABI (Build.SUPPORTED_ABIS) to a Termux bootstrap arch. */
    fun archForAbi(abi: String): String? = when (abi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86" -> "i686"
        "x86_64" -> "x86_64"
        else -> null
    }

    fun deviceArch(): String? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { archForAbi(it) }

    /** $PREFIX — where the bootstrap lives inside this app's private files. */
    fun prefixDir(filesDir: File): File =
        File(filesDir, "data/data/com.termux/files/usr")

    fun stagingDir(filesDir: File): File =
        File(filesDir, "data/data/com.termux/files/usr-staging")

    fun homeDir(filesDir: File): File =
        File(filesDir, "data/data/com.termux/files/home")

    fun isInstalled(filesDir: File): Boolean =
        File(prefixDir(filesDir), "bin/bash").exists()

    /**
     * Moves an already-installed bootstrap from the old layout
     * (`usr/` + `home/` at the files-dir root) into the new real-subtree
     * layout, so a phone that already downloaded the ~30 MB bootstrap
     * does not have to download it again. No-op when there is nothing
     * to move. A rename inside one files dir is a single syscall.
     */
    fun migrateLayout(filesDir: File) {
        val newPrefix = prefixDir(filesDir)
        val oldPrefix = File(filesDir, "usr")
        if (oldPrefix.exists() && !newPrefix.exists()) {
            newPrefix.parentFile?.mkdirs()
            oldPrefix.renameTo(newPrefix)
        }
        val newHome = homeDir(filesDir)
        val oldHome = File(filesDir, "home")
        if (oldHome.exists() && !newHome.exists()) {
            newHome.parentFile?.mkdirs()
            oldHome.renameTo(newHome)
        }
        // Leftover staging dir from the old layout (never the new one).
        File(filesDir, "usr-staging").deleteRecursively()
    }

    /**
     * Downloads (if needed) and installs the bootstrap for this device.
     * Returns true only when `usr/bin/bash` exists afterwards.
     * Progress is 0..1 across download + extraction.
     */
    suspend fun downloadAndInstall(
        filesDir: File,
        cacheDir: File,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            migrateLayout(filesDir)
            if (isInstalled(filesDir)) {
                onLog("Termux runtime already installed.")
                return@withContext true
            }
            val arch = deviceArch()
                ?: run { onLog("এই ফোনের CPU (ABI) সাপোর্ট করে না।"); return@withContext false }
            val expectedSha = CHECKSUMS[arch]
                ?: run { onLog("No bootstrap checksum for $arch."); return@withContext false }

            val zipFile = File(cacheDir, "bootstrap-$arch.zip")
            if (!zipFile.exists() || sha256Hex(zipFile) != expectedSha) {
                onLog("Downloading Termux runtime (~30 MB) for $arch…")
                download(downloadUrl(arch), zipFile, expectedSha, onProgress, onLog)
                    ?: return@withContext false
            } else {
                onLog("Bootstrap zip already downloaded, verifying…")
            }
            onProgress(0.55f)
            onLog("Extracting Termux runtime…")
            extractBootstrap(zipFile, stagingDir(filesDir), onLog)
            onProgress(0.9f)

            // Move staging into place (TermuxInstaller does the same rename).
            val prefix = prefixDir(filesDir)
            if (prefix.exists()) prefix.deleteRecursively()
            if (!stagingDir(filesDir).renameTo(prefix)) {
                onLog("Could not move runtime into place.")
                return@withContext false
            }
            homeDir(filesDir).mkdirs()
            onProgress(1f)
            onLog("Termux runtime installed.")
            isInstalled(filesDir)
        } catch (e: Exception) {
            onLog("Bootstrap failed: ${e.message}")
            runCatching { stagingDir(filesDir).deleteRecursively() }
            false
        }
    }

    /** Downloads the zip and verifies its SHA-256. Null on any failure. */
    private fun download(
        url: String,
        dest: File,
        expectedSha: String,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): File? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "AyanAgent-TermuxBootstrap")
            }
            if (connection.responseCode !in 200..299) {
                onLog("Download failed: HTTP ${connection.responseCode}")
                return null
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total) * 0.5f)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expectedSha) {
                onLog("Checksum mismatch for bootstrap zip.")
                dest.delete()
                return null
            }
            return dest
        } catch (e: Exception) {
            onLog("Download error: ${e.message}")
            dest.delete()
            return null
        } finally {
            connection?.disconnect()
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts a bootstrap zip into [stagingDir]. Ports TermuxInstaller's
     * logic: `SYMLINKS.txt` lines are `target←link-path` (the ← is U+2190);
     * executables under bin/, libexec and the apt helpers get 0700.
     * Uses java.nio APIs (not android.system.Os) so it also works in tests.
     */
    fun extractBootstrap(zipFile: File, stagingDir: File, onLog: (String) -> Unit = {}) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val symlinks = ArrayList<Pair<String, String>>()
        val buffer = ByteArray(8192)
        ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    // Do NOT close this reader: it would close the ZipInputStream
                    // and the remaining entries could not be read (TermuxInstaller
                    // reads the lines the same way, without closing).
                    val reader = zipInput.bufferedReader()
                    var line = reader.readLine()
                    while (line != null) {
                        val parts = line.split("←")
                        require(parts.size == 2) { "Malformed symlink line: $line" }
                        symlinks.add(parts[0] to parts[1])
                        line = reader.readLine()
                    }
                } else {
                    val target = File(stagingDir, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var read: Int
                            while (zipInput.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                        if (name.startsWith("bin/") || name.startsWith("libexec") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                        ) {
                            target.setReadable(true, true)
                            target.setWritable(true, true)
                            target.setExecutable(true, true)
                        }
                    }
                }
                entry = zipInput.nextEntry
            }
        }
        require(symlinks.isNotEmpty()) { "No SYMLINKS.txt encountered" }
        for ((target, linkPath) in symlinks) {
            val link = File(stagingDir, linkPath)
            link.parentFile?.mkdirs()
            if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
            Files.createSymbolicLink(link.toPath(), File(target).toPath())
        }
        onLog("Extracted ${stagingDir.absolutePath}")
    }
}
