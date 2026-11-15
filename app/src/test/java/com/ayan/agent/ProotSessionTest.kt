package com.ayan.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayan.agent.data.ProotSession
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3 of the real Termux runtime: proot argv + guest env.
 * Pure logic — no proot binary or device is needed for these tests.
 * The argv must match proot-distro's canonical termux-type login:
 * the prefix is a real subtree of the rootfs (never bound in), login
 * as the inner command, and NO fake-root / link2symlink flags
 * (a Termux guest runs as the app UID).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProotSessionTest {

    private fun filesDir(): File {
        val root = Files.createTempDirectory("proot-test").toFile()
        File(root, "data/data/com.termux/files/usr/bin").mkdirs()
        File(root, "data/data/com.termux/files/usr/bin/login").writeText("x")
        File(root, "data/data/com.termux/files/usr/bin/bash").writeText("x")
        File(root, "data/data/com.termux/files/home").mkdirs()
        return root
    }

    @Test
    fun argvUsesLoginAndNeverBindsPrefixOrHome() {
        val files = filesDir()
        val proot = File(files, "proot")
        val argv = ProotSession.buildArgv(files, proot, "pkg install -y python")

        assertEquals(proot.absolutePath, argv[0])
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("--sysvipc"))
        assertTrue(argv.contains("-L"))
        assertTrue(argv.contains("--rootfs=${files.absolutePath}"))
        assertTrue(argv.contains("--cwd=/data/data/com.termux/files/home"))
        assertTrue(argv.contains("--bind=/dev"))
        assertTrue(argv.contains("--bind=/proc"))
        assertTrue(argv.contains("--bind=/sys"))
        assertTrue(argv.contains("--bind=/system"))
        // The prefix and home are real subtrees of the rootfs — binding
        // them in broke proot's interpreter lookup on a real phone.
        assertFalse(argv.any { it.endsWith(":/data/data/com.termux/files/usr") })
        assertFalse(argv.any { it.endsWith(":/data/data/com.termux/files/home") })
        // Inner command: the bootstrap's own login, with the pkg command.
        val loginIdx = argv.indexOf("/data/data/com.termux/files/usr/bin/login")
        assertTrue(loginIdx > 0)
        assertEquals("-c", argv[loginIdx + 1])
        assertEquals("pkg install -y python", argv[loginIdx + 2])
    }

    @Test
    fun argvHasNoFakeRootOrLink2Symlink() {
        val argv = ProotSession.buildArgv(filesDir(), File("/tmp/proot"), null)
        assertFalse(argv.contains("-0"))
        assertFalse(argv.any { it.startsWith("--change-id") })
        assertFalse(argv.contains("--link2symlink"))
    }

    @Test
    fun argvFallsBackToBashWhenLoginMissing() {
        val files = filesDir()
        File(files, "data/data/com.termux/files/usr/bin/login").delete()
        val argv = ProotSession.buildArgv(files, File("/tmp/proot"), null)
        assertTrue(argv.contains("/data/data/com.termux/files/usr/bin/bash"))
        assertTrue(argv.contains("--login"))
        assertFalse(argv.contains("/data/data/com.termux/files/usr/bin/login"))
    }

    @Test
    fun terminalSessionConfigSplitsShellArgsEnv() {
        val files = filesDir()
        val proot = File(files, "proot")
        val config = ProotSession.terminalSessionConfig(files, proot)
        assertTrue(config != null)
        config!!
        assertEquals(proot.absolutePath, config.shellPath)
        assertTrue(config.args.contains("--rootfs=${files.absolutePath}"))
        assertTrue(config.args.contains("/data/data/com.termux/files/usr/bin/login"))
        assertTrue(config.env.contains("PREFIX=/data/data/com.termux/files/usr"))
        assertTrue(config.env.contains("HOME=/data/data/com.termux/files/home"))
        assertFalse(config.env.any { it.startsWith("LD_PRELOAD=") })
        assertFalse(config.env.any { it.startsWith("LD_LIBRARY_PATH=") })
    }

    @Test
    fun terminalSessionConfigNullWithoutBootstrap() {
        val root = Files.createTempDirectory("proot-test-noboot").toFile()
        assertEquals(null, ProotSession.terminalSessionConfig(root, File(root, "proot")))
    }

    @Test
    fun extractFromApkPullsProotForAbi() {
        // A fake APK zip holding lib/arm64-v8a/libproot_exec.so.
        val apk = File.createTempFile("fake-apk", ".apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libproot_exec.so"))
            zip.write("#!/bin/sh\necho proot".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("lib/x86_64/libproot_exec.so"))
            zip.write("x86".toByteArray())
            zip.closeEntry()
        }
        val dest = File.createTempFile("proot-out", ".bin")
        dest.delete()
        assertTrue(ProotSession.extractFromApk(apk.absolutePath, "arm64-v8a", dest))
        assertTrue(dest.exists())
        assertTrue(dest.canExecute())
        assertEquals("#!/bin/sh\necho proot", dest.readText())
        // A missing ABI entry returns false instead of crashing.
        val missing = File.createTempFile("proot-missing", ".bin")
        missing.delete()
        assertFalse(ProotSession.extractFromApk(apk.absolutePath, "armeabi-v7a", missing))
        assertFalse(missing.exists())
    }

    @Test
    fun guestEnvSetsProotTmpDirOnHost() {
        val files = filesDir()
        val env = ProotSession.guestEnv(files)
        assertEquals(File(files, "proot-tmp").absolutePath, env["PROOT_TMP_DIR"])
        // The guest's own TMPDIR stays a guest path.
        assertEquals("/data/data/com.termux/files/usr/tmp", env["TMPDIR"])
    }

    @Test
    fun prepareGuestFilesCreatesDirsAndResolvConf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProotSession.prepareGuestFiles(context)
        val filesDir = context.filesDir
        assertTrue(File(filesDir, "proot-shm").isDirectory)
        assertTrue(File(filesDir, "proot-tmp").isDirectory)
        assertTrue(File(filesDir, "data/data/com.termux/files/usr/tmp").isDirectory)
        assertTrue(File(filesDir, "data/data/com.termux/files/home").isDirectory)
        assertTrue(File(filesDir, "data/data/com.termux/files/cache").isDirectory)
        val resolv = File(filesDir, "data/data/com.termux/files/usr/etc/resolv.conf")
        assertTrue(resolv.exists())
        // No active network under Robolectric, so the public fallback is used.
        assertTrue(resolv.readText().contains("nameserver 8.8.8.8"))
    }

    @Test
    fun guestEnvSetsProotLoaderWhenGiven() {
        val loader = File("/data/app/lib/arm64/libproot_loader.so")
        val env = ProotSession.guestEnv(filesDir(), loader)
        assertEquals(loader.absolutePath, env["PROOT_LOADER"])
        // Without a loader path proot extracts its own (old behaviour).
        assertFalse(ProotSession.guestEnv(filesDir()).containsKey("PROOT_LOADER"))
    }

    @Test
    fun terminalSessionConfigPassesProotLoaderThrough() {
        val files = filesDir()
        val loader = File("/data/app/lib/arm64/libproot_loader.so")
        val config = ProotSession.terminalSessionConfig(files, File(files, "proot"), loader)
        assertTrue(config != null)
        assertTrue(config!!.env.contains("PROOT_LOADER=${loader.absolutePath}"))
    }

    @Test
    fun guestEnvHasTermuxPathsAndNoLoaderVars() {
        val env = ProotSession.guestEnv(filesDir())
        assertEquals("/data/data/com.termux/files/usr", env["PREFIX"])
        assertEquals("/data/data/com.termux/files/home", env["HOME"])
        assertEquals("/data/data/com.termux/files/usr/bin", env["PATH"])
        assertEquals("/data/data/com.termux/files/usr/tmp", env["TMPDIR"])
        assertFalse(env.containsKey("LD_PRELOAD"))
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
    }
}
