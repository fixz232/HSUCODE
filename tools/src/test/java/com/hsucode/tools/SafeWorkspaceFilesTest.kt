package com.hsucode.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SafeWorkspaceFilesTest {

    @Test
    fun replacesExistingFileWithoutLeavingPartial() {
        val directory = Files.createTempDirectory("hsucode-atomic-write").toFile()
        try {
            val target = directory.resolve("result.txt")
            target.writeText("old")

            SafeWorkspaceFiles.writeTextAtomically(target, "new")

            assertEquals("new", target.readText())
            check(directory.listFiles().orEmpty().none { it.name.endsWith(".partial") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
