package com.ayan.agent

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
 * rootfs + binds for usr/home, login as the inner command, and
 * NO fake-root / link2symlink flags (a Termux guest runs as the app UID).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProotSessionTest {

    private fun filesDir(): File {
        val root = Files.createTempDirectory("proot-test").toFile()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/login").writeText("x")
        File(root, "usr/bin/bash").writeText("x")
        File(root, "home").mkdirs()
        return root
    }

    @Test
    fun argvUsesLoginAndBindsUsrAndHome() {
        val files = filesDir()
        val proot = File(files, "proot")
        val argv = ProotSession.buildArgv(files, proot, "pkg install -y python")

        assertEquals(proot.absolutePath, argv[0])
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("--sysvipc"))
        assertTrue(argv.contains("-L"))
        assertTrue(argv.contains("--rootfs=${files.absolutePath}"))
        assertTrue(argv.contains("--cwd=/data/data/com.termux/files/home"))
        assertTrue(argv.contains("--bind=${File(files, "usr").absolutePath}:/data/data/com.termux/files/usr"))
        assertTrue(argv.contains("--bind=${File(files, "home").absolutePath}:/data/data/com.termux/files/home"))
        assertTrue(argv.contains("--bind=/dev"))
        assertTrue(argv.contains("--bind=/proc"))
        assertTrue(argv.contains("--bind=/sys"))
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
        File(files, "usr/bin/login").delete()
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
