package com.hsucode.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceFileOpsTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("hsucode-workspace-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun resolveRejectsPathOutsideWorkspace() {
        assertTrue(WorkspaceFileOps.resolve(root, "notes/todo.md").isSuccess)
        assertTrue(WorkspaceFileOps.resolve(root, "../outside.txt").isFailure)
        assertTrue(WorkspaceFileOps.resolve(root, "/tmp/outside.txt").isFailure)
    }

    @Test
    fun unzipExtractsSafeArchive() {
        val archive = File(root, "safe.zip")
        writeZip(archive, "nested/readme.txt" to "hello")

        val result = WorkspaceFileOps.unzip(root, "safe.zip", "unpacked")

        assertEquals(1, result.getOrThrow())
        assertEquals("hello", File(root, "unpacked/nested/readme.txt").readText())
    }

    @Test
    fun unzipRejectsZipSlipEntry() {
        val archive = File(root, "unsafe.zip")
        writeZip(archive, "../outside.txt" to "blocked")
        val outside = File(root.parentFile, "outside.txt")
        outside.delete()

        val result = WorkspaceFileOps.unzip(root, "unsafe.zip", "unpacked")

        assertTrue(result.isFailure)
        assertFalse(outside.exists())
    }

    @Test
    fun globDoubleStarMatchesFilesAtAnyDepth() {
        File(root, "top.kt").writeText("top")
        File(root, "nested/deep.kt").apply { parentFile.mkdirs(); writeText("deep") }

        val result = WorkspaceFileOps.glob(root, "**/*.kt").getOrThrow().map { it.relativePath }.toSet()

        assertEquals(setOf("top.kt", "nested/deep.kt"), result)
    }

    @Test
    fun moveRejectsMovingDirectoryIntoItself() {
        File(root, "src/file.txt").apply { parentFile.mkdirs(); writeText("x") }

        assertTrue(WorkspaceFileOps.move(root, "src", "src/nested/src").isFailure)
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
