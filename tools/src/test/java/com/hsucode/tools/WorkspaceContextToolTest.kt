package com.hsucode.tools

import com.hsucode.core.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Regression tests for conversation-scoped file output paths. */
class WorkspaceContextToolTest {

    private lateinit var fallbackRoot: File
    private lateinit var conversationRoot: File
    private lateinit var savedRoot: String

    @Before
    fun setUp() {
        fallbackRoot = Files.createTempDirectory("hsucode-fallback").toFile()
        conversationRoot = Files.createTempDirectory("hsucode-conversation").toFile()
        savedRoot = WorkspaceContext.workspaceRoot
        WorkspaceContext.workspaceRoot = fallbackRoot.absolutePath
    }

    @After
    fun tearDown() {
        WorkspaceContext.workspaceRoot = savedRoot
        fallbackRoot.deleteRecursively()
        conversationRoot.deleteRecursively()
    }

    @Test
    fun relativePathResolvesAgainstConversationWorkspace() = runTest {
        withContext(WorkspaceThreadElement { conversationRoot.absolutePath to 42L }) {
            val resolved = PathResolver.resolve("notes/today.md")
            assertEquals(File(conversationRoot, "notes/today.md").canonicalPath, resolved)
        }
    }

    @Test
    fun fileWriteUsesConversationWorkspaceAfterDispatcherSwitch() = runTest {
        val result = withContext(WorkspaceThreadElement { conversationRoot.absolutePath to 42L }) {
            FileWriteTool().execute(mapOf("path" to "exports/result.txt", "content" to "done"))
        }

        assertTrue(result is ToolResult.Success)
        assertTrue(File(conversationRoot, "exports/result.txt").isFile)
        assertEquals("done", File(conversationRoot, "exports/result.txt").readText())
        assertFalse(File(fallbackRoot, "exports/result.txt").exists())
    }
}
