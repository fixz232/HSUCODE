package com.hsucode.app

import com.hsucode.app.root.ExecResult
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.Main
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the real Termux PTY alive while the terminal page is temporarily detached.
 * Agent commands can therefore run in the same shell that the user sees.
 */
internal object TermuxPtySessionManager {
    private val lock = Mutex()
    private val markerCounter = AtomicInteger(0)
    private const val DEFAULT_SESSION = "terminal"
    private const val AGENT_SESSION = "agent"
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    @Volatile private var activeSessionId: String = DEFAULT_SESSION

    data class Status(val id: String, val running: Boolean, val attached: Boolean)

    fun current(sessionId: String = activeSessionId): TerminalSession? = sessions[sessionId]?.takeIf { it.isRunning }

    fun sessionIds(): List<String> = (sessions.keys + DEFAULT_SESSION + activeSessionId).distinct().sorted()

    fun activeId(): String = activeSessionId

    fun select(sessionId: String) { activeSessionId = sessionId.ifBlank { DEFAULT_SESSION } }

    fun createSession(): String {
        var index = 2
        var candidate = "terminal-$index"
        while (sessions.containsKey(candidate)) candidate = "terminal-${++index}"
        activeSessionId = candidate
        return candidate
    }

    fun statuses(): List<Status> = sessionIds().map { id -> Status(id, current(id)?.isRunning == true, id == activeSessionId) }

    fun obtain(client: TerminalSessionClient, sessionId: String = activeSessionId): TerminalSession? {
        val id = sessionId.ifBlank { DEFAULT_SESSION }
        activeSessionId = id
        val existing = current(id)
        if (existing != null) {
            existing.updateTerminalSessionClient(client)
            return existing
        }
        return ProotLinuxEnvironment.createPtyTerminalSession(client)?.also { sessions[id] = it }
    }

    fun detach(detached: TerminalSession) {
        if (!sessions.containsValue(detached)) return
        // Keep the PTY alive so Agent commands and the next terminal view share state.
        Unit
    }

    fun bindClient(client: TerminalSessionClient, sessionId: String = activeSessionId) {
        current(sessionId)?.updateTerminalSessionClient(client)
    }

    fun restart(sessionId: String = activeSessionId) {
        sessions.remove(sessionId)?.finishIfRunning()
    }

    /** Rebuild all shells after a cwd change; a PTY cannot change its process cwd externally. */
    fun restartAll() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
    }

    fun closeSession(sessionId: String) {
        if (sessionId == DEFAULT_SESSION) return
        sessions.remove(sessionId)?.finishIfRunning()
        if (activeSessionId == sessionId) activeSessionId = DEFAULT_SESSION
    }

    fun close() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
    }

    suspend fun execute(command: String, sessionId: String = AGENT_SESSION): ExecResult? = lock.withLock {
        if (WorkspaceRuntime.backend() != WorkspaceRuntime.Backend.PROOT_UBUNTU) return@withLock null
        val active = current(sessionId) ?: ensureHeadless(sessionId) ?: return@withLock null
        if (runCatching { active.getEmulator() }.getOrNull() == null) return@withLock null
        val text = command.trim()
        if (text.isBlank()) return@withLock ExecResult("", "命令不能为空", 2, 0L, false)
        val startedAt = System.currentTimeMillis()
        val marker = "__HSUCODE_AGENT_DONE_${markerCounter.incrementAndGet()}__"
        val before = transcript(active)
        val payload = "$text\nprintf '\\n${marker}%s\\n' \"\\$?\"\n"
        withContext(Dispatchers.IO) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            active.write(bytes, 0, bytes.size)
        }
        val timeoutMs = WorkspaceCommandPolicy.timeoutSeconds(text) * 1_000L
        var latest = before
        var transcriptWasTruncated = false
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(60)
            latest = transcript(active)
            transcriptWasTruncated = !latest.startsWith(before) && latest.length > 12_000
            val delta = transcriptDelta(before, latest)
            val markerIndex = delta.indexOf(marker)
            if (markerIndex >= 0) {
                val codeText = delta.substring(markerIndex + marker.length).lineSequence().firstOrNull().orEmpty()
                val exitCode = codeText.trim().toIntOrNull() ?: 1
                val output = delta.substring(0, markerIndex).trim()
                return@withLock ExecResult(output, "", exitCode, System.currentTimeMillis() - startedAt, exitCode == 0)
            }
        }
        // Interrupt the current command, then verify the shell accepts a fresh control probe.
        withContext(Dispatchers.IO) { active.write(byteArrayOf(3), 0, 1) }
        delay(180)
        val healthy = shellHealthy(active)
        val detail = buildString {
            append(if (transcriptWasTruncated) "终端输出已截断，结果未知" else "命令执行超时")
            append(if (healthy) "；shell 仍可用" else "；shell 无响应，已重建")
        }
        if (!healthy) restart(sessionId)
        ExecResult(transcriptDelta(before, latest).takeLast(12_000), detail, if (healthy) 124 else 125, System.currentTimeMillis() - startedAt, false)
    }

    private fun transcript(active: TerminalSession): String = runCatching {
        active.getEmulator()?.screen?.getTranscriptText().orEmpty()
    }.getOrDefault("")

    private fun transcriptDelta(before: String, after: String): String = when {
        after.startsWith(before) -> after.substring(before.length)
        after.length > 12_000 -> after.takeLast(12_000)
        else -> after
    }

    private suspend fun ensureHeadless(sessionId: String): TerminalSession? {
        if (!withContext(Dispatchers.IO) { ProotLinuxEnvironment.preparePtyTerminal() }) return null
        return withContext(Main.immediate) {
            obtain(HeadlessClient, sessionId)?.also { active ->
                if (active.getEmulator() == null) active.initializeEmulator(80, 24)
            }
        }
    }

    private suspend fun shellHealthy(active: TerminalSession): Boolean {
        val marker = "__HSUCODE_HEALTH_${markerCounter.incrementAndGet()}__"
        val before = transcript(active)
        withContext(Dispatchers.IO) {
            val bytes = "printf '\\n${marker}\\n'\n".toByteArray(Charsets.UTF_8)
            active.write(bytes, 0, bytes.size)
        }
        repeat(10) {
            delay(80)
            if (transcriptDelta(before, transcript(active)).contains(marker)) return true
        }
        return false
    }

    private object HeadlessClient : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) = Unit
        override fun onTitleChanged(session: TerminalSession) = Unit
        override fun onSessionFinished(session: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }
}
