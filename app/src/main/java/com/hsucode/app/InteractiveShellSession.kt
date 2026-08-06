package com.hsucode.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one shell process alive so `cd`, `export` and installed tool state survive
 * between commands. It is intentionally a pipe-backed shell, not a fake root shell:
 * the process still runs with the app UID and can only access the selected workspace.
 */
internal class InteractiveShellSession(
    private val process: Process,
    private val onOutput: (String) -> Unit,
) : Closeable {
    private companion object { const val MAX_CAPTURE_CHARS = 12_000 }
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val markerCounter = AtomicInteger(0)
    private val lock = Any()
    private var pending: Pending? = null
    @Volatile
    private var closed = false

    init {
        Thread {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    val active = synchronized(lock) { pending }
                    if (active != null && line.startsWith(active.marker)) {
                        val code = line.removePrefix(active.marker).trim().toIntOrNull() ?: 1
                        synchronized(lock) { if (pending === active) pending = null }
                        active.deferred.complete(code)
                    } else {
                        active?.let { capture ->
                            if (capture.output.length < MAX_CAPTURE_CHARS) {
                                val remaining = MAX_CAPTURE_CHARS - capture.output.length
                                capture.output.append(line.take(remaining.coerceAtLeast(0)))
                                if (capture.output.length < MAX_CAPTURE_CHARS) capture.output.append('\n')
                            }
                        }
                        onOutput(line)
                    }
                }
                failPending(IOException("终端会话已关闭"))
            } catch (error: Exception) {
                failPending(error)
            }
        }.apply {
            name = "hsucode-terminal-output"
            isDaemon = true
            start()
        }
    }

    suspend fun execute(command: String): Int = executeWithOutput(command).exitCode

    /** Executes one command and returns the output observed before the completion marker. */
    suspend fun executeWithOutput(command: String): Result {
        val commandText = command.trimEnd()
        require(commandText.isNotBlank()) { "命令不能为空" }
        val marker = "__HSUCODE_DONE_${markerCounter.incrementAndGet()}__"
        val deferred = CompletableDeferred<Int>()
        val output = StringBuilder()
        synchronized(lock) {
            check(!closed) { "终端会话已关闭" }
            check(pending == null) { "终端仍在执行上一条命令" }
            pending = Pending(marker, deferred, output)
            try {
                writer.write(commandText)
                writer.newLine()
                writer.write("printf '\\n${marker}%s\\n' \"\\$?\"")
                writer.newLine()
                writer.flush()
            } catch (error: IOException) {
                pending = null
                throw error
            }
        }
        return try {
            Result(deferred.await(), output.toString())
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    /** Sends a byte sequence directly to the shell stdin (TAB, ESC, arrows, etc.). */
    fun send(bytes: ByteArray) {
        synchronized(lock) {
            if (closed) return
            runCatching {
                process.outputStream.write(bytes)
                process.outputStream.flush()
            }
        }
    }

    fun isAlive(): Boolean = !closed && process.isAlive

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending?.deferred?.completeExceptionally(IOException("终端会话已关闭"))
            pending = null
            runCatching { writer.close() }
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun failPending(error: Exception) {
        synchronized(lock) {
            if (pending != null) {
                pending?.deferred?.completeExceptionally(error)
                pending = null
            }
            closed = true
        }
    }

    data class Result(val exitCode: Int, val output: String)

    private data class Pending(
        val marker: String,
        val deferred: CompletableDeferred<Int>,
        val output: StringBuilder,
    )
}
