package com.hsucode.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.hsucode.app.root.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * 可视终端(Application 级)。三路输出汇聚到这里、实时上屏:
 *  - 用户在终端页手输的命令;
 *  - Linux 环境【部署 / 工具安装】的流式输出(LinuxEnvironment.outputSink);
 *  - AI 通过 env_exec 工具在环境里跑的命令(让用户看到 AI 在操作什么)。
 */
class TerminalState {
    val lines: SnapshotStateList<String> = mutableStateListOf(
        "HSUCODE 终端 — 优先使用免 Root PRoot Ubuntu；未安装时在 Android Shell 工作区执行。",
        ""
    )
    var running by mutableStateOf(false)
        private set

    private val maxLines = 4000
    private val commandMutex = Mutex()
    private var interactiveBackend: WorkspaceRuntime.Backend? = null
    private var interactiveSession: InteractiveShellSession? = null

    @Synchronized
    fun appendChunk(s: String) {
        if (s.isEmpty()) { lines.add(""); return }
        for (line in s.split('\n')) lines.add(line)
        while (lines.size > maxLines) lines.removeAt(0)
    }

    fun clear() { lines.clear() }

    /** 执行一条命令，并在 PRoot/Android 工作区中复用同一个 shell 会话。 */
    suspend fun run(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        commandMutex.withLock {
            running = true
            appendChunk("$ $c")
            try {
                val backend = WorkspaceRuntime.backend()
                if (backend == WorkspaceRuntime.Backend.ROOT_CHROOT) {
                    val res = withContext(Dispatchers.IO) { WorkspaceRuntime.runStreaming(c) { appendChunk(it) } }
                    appendChunk("[exit ${res.exitCode}]")
                } else {
                    val session = ensureInteractiveSession(backend)
                    val exit = withContext(Dispatchers.IO) { session.execute(c) }
                    appendChunk("[exit $exit]")
                }
            } catch (e: Exception) {
                appendChunk("[错误] ${e.message ?: "终端会话不可用"}")
            } finally {
                running = false
            }
        }
    }

    /** Agent-facing execution path that reuses the same persistent fallback shell. */
    suspend fun runForAgent(cmd: String): ExecResult = commandMutex.withLock {
        val c = cmd.trim()
        if (c.isEmpty()) return@withLock ExecResult("", "命令不能为空", 2, 0L, false)
        running = true
        appendChunk("$ [AI] $c")
        val startedAt = System.currentTimeMillis()
        try {
            val backend = WorkspaceRuntime.backend()
            if (backend == WorkspaceRuntime.Backend.ROOT_CHROOT) {
                return@withLock withContext(Dispatchers.IO) {
                    WorkspaceRuntime.runStreaming(c) { appendChunk(it) }
                }
            }
            val session = ensureInteractiveSession(backend)
            val timeoutMs = WorkspaceCommandPolicy.timeoutSeconds(c) * 1_000L
            val result = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { session.executeWithOutput(c) }
            }
            if (result == null) {
                session.close()
                interactiveSession = null
                interactiveBackend = null
                appendChunk("[超时] 命令超过 ${timeoutMs / 1_000}s，已停止")
                ExecResult("", "命令执行超时", 124, System.currentTimeMillis() - startedAt, false)
            } else {
                appendChunk("[AI exit ${result.exitCode}]")
                ExecResult(result.output, "", result.exitCode, System.currentTimeMillis() - startedAt, result.exitCode == 0)
            }
        } catch (error: Exception) {
            appendChunk("[错误] ${error.message ?: "终端会话不可用"}")
            ExecResult("", error.message.orEmpty(), -1, System.currentTimeMillis() - startedAt, false)
        } finally {
            running = false
        }
    }

    fun sendControl(bytes: ByteArray) {
        interactiveSession?.send(bytes)
    }

    fun stop() {
        interactiveSession?.close()
        interactiveSession = null
        interactiveBackend = null
        running = false
        appendChunk("[已停止当前终端会话]")
    }

    fun close() {
        interactiveSession?.close()
        interactiveSession = null
        interactiveBackend = null
    }

    private fun ensureInteractiveSession(backend: WorkspaceRuntime.Backend): InteractiveShellSession {
        val current = interactiveSession
        if (current != null && interactiveBackend == backend && current.isAlive()) return current
        current?.close()
        val created = WorkspaceRuntime.startInteractiveSession { appendChunk(it) }
            ?: error("${WorkspaceRuntime.title()} 尚未就绪")
        interactiveBackend = backend
        interactiveSession = created
        return created
    }
}
