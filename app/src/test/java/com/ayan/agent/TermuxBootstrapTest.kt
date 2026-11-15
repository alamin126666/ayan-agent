package com.ayan.agent

import com.ayan.agent.data.TermuxBootstrap
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 2 of the real Termux runtime: bootstrap download + extraction.
 * The extraction is tested with a small fake bootstrap zip (no network):
 * files land in staging, SYMLINKS.txt links are created, and bin/ files
 * get owner-only (0700) permissions, exactly like TermuxInstaller does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxBootstrapTest {

    private fun fakeBootstrapZip(dir: File): File {
        val zip = File(dir, "bootstrap-aarch64.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("bin/bash"))
            out.write("#!/bin/bash\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("lib/test.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("SYMLINKS.txt"))
            out.write("bin/bash←bin/sh\n".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun extractCreatesFilesSymlinksAndPermissions() {
        val root = Files.createTempDirectory("bootstrap-test").toFile()
        val staging = File(root, "usr-staging")
        TermuxBootstrap.extractBootstrap(fakeBootstrapZip(root), staging)

        val bash = File(staging, "bin/bash")
        assertTrue(bash.exists())
        assertEquals("#!/bin/bash\n", bash.readText())
        assertTrue("bin/bash must be executable", bash.canExecute())

        val sh = File(staging, "bin/sh")
        assertTrue(Files.isSymbolicLink(sh.toPath()))
        assertEquals(File("bin/bash").toPath(), Files.readSymbolicLink(sh.toPath()))
        assertTrue(File(staging, "lib/test.txt").readText() == "hello")
    }

    @Test
    fun isInstalledChecksUsrBinBash() {
        val root = Files.createTempDirectory("bootstrap-test2").toFile()
        assertFalse(TermuxBootstrap.isInstalled(root))
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/bash").writeText("x")
        assertTrue(TermuxBootstrap.isInstalled(root))
    }

    @Test
    fun archMappingAndChecksums() {
        assertEquals("aarch64", TermuxBootstrap.archForAbi("arm64-v8a"))
        assertEquals("arm", TermuxBootstrap.archForAbi("armeabi-v7a"))
        assertEquals("i686", TermuxBootstrap.archForAbi("x86"))
        assertEquals("x86_64", TermuxBootstrap.archForAbi("x86_64"))
        assertEquals(null, TermuxBootstrap.archForAbi("mips"))
        assertEquals(4, TermuxBootstrap.CHECKSUMS.size)
        TermuxBootstrap.CHECKSUMS.values.forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{64}")))
        }
        assertTrue(TermuxBootstrap.downloadUrl("aarch64").contains("bootstrap-aarch64.zip"))
    }
}
