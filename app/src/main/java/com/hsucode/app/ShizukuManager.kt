package com.hsucode.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException as KotlinCancellationException

enum class ShizukuBackend { UNAVAILABLE, ADB_SHELL, ROOT }

data class ShizukuState(
    val binderConnected: Boolean = false,
    val permissionGranted: Boolean = false,
    val installed: Boolean = false,
    val backendUid: Int? = null,
    val serverVersion: Int? = null,
    val backend: ShizukuBackend = ShizukuBackend.UNAVAILABLE,
    val lastError: String? = null,
    val label: String = "未连接"
) {
    /** This is intentionally stricter than just pingBinder: a stale or unexpected
     * backend must never be presented as a usable elevated channel. */
    val usable: Boolean
        get() = binderConnected && permissionGranted && backendUid in setOf(0, 2000)
}

data class ShizukuCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean = false,
)

data class ShizukuSessionSnapshot(
    val id: String,
    val running: Boolean,
    val startedAt: Long,
)

data class ShizukuProcessSnapshot(
    val id: String,
    val command: String,
    val running: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val startedAt: Long,
)

/**
 * Official Shizuku shell bridge. Commands are created with IShizukuService.newProcess,
 * so they run with the service backend identity (ADB shell uid 2000 or root uid 0).
 * All state is observable and all process handles are closed on timeout/death.
 */
object ShizukuManager {
    private const val TAG = "HsucodeShizuku"
    const val REQUEST_CODE = 0x4853
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/"
    private const val COMMAND_TIMEOUT_MS = 30_000L
    private const val MAX_OUTPUT = 12_000
    private const val MAX_BACKGROUND_OUTPUT = 16_000

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    private val _sessions = MutableStateFlow<List<ShizukuSessionSnapshot>>(emptyList())
    val sessions: StateFlow<List<ShizukuSessionSnapshot>> = _sessions.asStateFlow()
    private val _processes = MutableStateFlow<List<ShizukuProcessSnapshot>>(emptyList())
    val processes: StateFlow<List<ShizukuProcessSnapshot>> = _processes.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    private val interactiveSessions = ConcurrentHashMap<String, ShizukuInteractiveSession>()
    private val backgroundProcesses = ConcurrentHashMap<String, ManagedShizukuProcess>()
    private val markerCounter = AtomicInteger()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!initialized) {
            initialized = true
            runCatching {
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener {
                    interactiveSessions.values.toList().forEach { it.close() }
                    backgroundProcesses.values.toList().forEach { it.stop() }
                    refresh()
                }
                Shizuku.addRequestPermissionResultListener { requestCode, _ ->
                    if (requestCode == REQUEST_CODE) refresh()
                }
            }.onFailure { Log.w(TAG, "listener registration failed: ${it.message}") }
        }
        refresh()
    }

    @Synchronized
    fun refresh(): ShizukuState {
        val context = appContext
        val installed = context?.let { packageInstalled(it) } ?: false
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        val connected = runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            binder?.isBinderAlive == true
        val uid = if (connected) runCatching { Shizuku.getUid() }.getOrNull() else null
        val version = if (connected) runCatching { Shizuku.getVersion() }.getOrNull() else null
        val uidSupported = uid == 0 || uid == 2000
        val granted = connected && uidSupported && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val backend = when (uid) {
            0 -> ShizukuBackend.ROOT
            2000 -> ShizukuBackend.ADB_SHELL
            else -> ShizukuBackend.UNAVAILABLE
        }
        val error = when {
            !installed -> "未检测到 Shizuku 应用"
            !connected -> "Shizuku Binder 未连接或已停止"
            !uidSupported -> "Shizuku 后端 UID=$uid，不是 shell(2000)或 root(0)"
            !granted -> "HSUCODE 尚未获得 Shizuku 授权"
            else -> null
        }
        val label = when {
            !installed -> "未安装 Shizuku"
            !connected -> "Shizuku 未启动"
            !uidSupported -> "后端 UID 不受支持"
            !granted -> "等待 Shizuku 授权"
            backend == ShizukuBackend.ROOT -> "已授权 · root 后端"
            else -> "已授权 · shell UID 2000"
        }
        return ShizukuState(connected, granted, installed, uid, version, backend, error, label)
            .also { _state.value = it }
    }

    fun requestPermission(): Result<String> = runCatching {
        val current = refresh()
        require(current.binderConnected) { current.lastError ?: "请先启动 Shizuku 服务" }
        require(current.backendUid in setOf(0, 2000)) { current.lastError ?: "Shizuku 后端不可用" }
        if (!current.permissionGranted) Shizuku.requestPermission(REQUEST_CODE)
        "授权请求已发送，请在 Shizuku 中确认"
    }

    fun openShizuku(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return@runCatching false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun openShizukuDownload(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)

    fun installedVersion(context: Context = appContext ?: error("应用尚未初始化")): String? =
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            info.versionName
        }.getOrNull()

    suspend fun execute(command: String): ShizukuCommandResult = executeStreaming(command)

    suspend fun executeStreaming(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        onChunk: (text: String, stderr: Boolean) -> Unit = { _, _ -> },
    ): ShizukuCommandResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        require(refresh().usable) { state.value.lastError ?: "Shizuku 尚未授权" }
        val started = System.currentTimeMillis()
        val process = newProcess(command)
        val stdoutFd = process.getInputStream()
        val stderrFd = process.getErrorStream()
        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) { readCapped(stdoutFd, MAX_OUTPUT) { onChunk(it, false) } }
                val stderr = async(Dispatchers.IO) { readCapped(stderrFd, MAX_OUTPUT) { onChunk(it, true) } }
                val wait = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
                var timedOut = false
                val exitCode = try {
                    withTimeout(timeoutMs.coerceAtLeast(1_000L)) { wait.await() }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                    runCatching { process.destroy() }
                    124
                }
                val out = runCatching { stdout.await() }.getOrDefault("")
                val err = runCatching { stderr.await() }.getOrDefault("")
                ShizukuCommandResult(out, err, exitCode, System.currentTimeMillis() - started, timedOut)
            }
        } finally {
            runCatching { stdoutFd.close() }
            runCatching { stderrFd.close() }
            if (currentCoroutineContext().isActive) runCatching { process.destroy() }
        }
    }

    internal fun startInteractiveSession(
        id: String = "shizuku-${UUID.randomUUID().toString().take(8)}",
        onOutput: (String) -> Unit = {},
    ): ShizukuInteractiveSession {
        check(refresh().usable) { state.value.lastError ?: "Shizuku 尚未授权" }
        interactiveSessions[id]?.close()
        val session = ShizukuInteractiveSession(
            id,
            newProcess("export TERM=xterm-256color; export COLORTERM=truecolor; if command -v script >/dev/null 2>&1; then exec script -q -c 'sh -i' /dev/null; else exec sh -i; fi"),
            onOutput
        ) {
            interactiveSessions.remove(id)
            publishSessions()
        }
        interactiveSessions[id] = session
        publishSessions()
        return session
    }

    fun stopInteractiveSession(id: String) { interactiveSessions.remove(id)?.close(); publishSessions() }

    fun startBackgroundProcess(command: String, label: String = command.take(80)): String {
        check(refresh().usable) { state.value.lastError ?: "Shizuku 尚未授权" }
        require(command.isNotBlank()) { "命令不能为空" }
        val id = "proc-${UUID.randomUUID().toString().take(8)}"
        val process = ManagedShizukuProcess(id, command, newProcess(command), label) {
            publishProcesses()
        }
        backgroundProcesses[id] = process
        publishProcesses()
        return id
    }

    fun stopBackgroundProcess(id: String): Boolean = backgroundProcesses.remove(id)?.let {
        it.stop(); publishProcesses(); true
    } ?: false

    fun processSnapshot(id: String): ShizukuProcessSnapshot? = backgroundProcesses[id]?.snapshot()

    fun clearFinishedProcesses() {
        backgroundProcesses.entries.removeIf { !it.value.snapshot().running }
        publishProcesses()
    }

    private fun publishSessions() {
        _sessions.value = interactiveSessions.values.map { it.snapshot() }.sortedBy { it.id }
    }

    private fun publishProcesses() {
        _processes.value = backgroundProcesses.values.map { it.snapshot() }.sortedBy { it.startedAt }
    }

    private fun packageInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    private fun newProcess(command: String): IRemoteProcess {
        val binder = Shizuku.getBinder() ?: error("Shizuku Binder 为空")
        check(binder.isBinderAlive) { "Shizuku Binder 已失效" }
        val service = IShizukuService.Stub.asInterface(binder) ?: error("无法连接 Shizuku 服务")
        val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
        return process ?: error("Shizuku 无法创建 shell 进程")
    }

    private fun readCapped(
        fd: ParcelFileDescriptor,
        limit: Int,
        onChunk: (String) -> Unit,
    ): String {
        val result = StringBuilder()
        val buffer = ByteArray(8 * 1024)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val text = String(buffer, 0, count, StandardCharsets.UTF_8)
                runCatching { onChunk(text) }
                if (result.length < limit) result.append(text.take(limit - result.length))
            }
        }
        return if (result.length < limit) result.toString() else result.toString() + "\n[…输出已截断…]"
    }
}

internal class ShizukuInteractiveSession(
    val id: String,
    private val process: IRemoteProcess,
    private val onOutput: (String) -> Unit,
    private val onClosed: () -> Unit,
) : java.io.Closeable, CommandSession {
    private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream())
    private val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
    private val lock = Any()
    private var pending: Pending? = null
    @Volatile private var closed = false
    private val markerCounter = AtomicInteger()
    private val startedAt = System.currentTimeMillis()

    init {
        read(process.getInputStream(), false)
        read(process.getErrorStream(), true)
    }

    override suspend fun execute(command: String): Int = executeWithOutput(command).exitCode

    override suspend fun executeWithOutput(command: String): CommandSession.CommandResult {
        val text = command.trimEnd()
        require(text.isNotBlank()) { "命令不能为空" }
        val marker = "__HSUCODE_SHIZUKU_${markerCounter.incrementAndGet()}__"
        val deferred = kotlinx.coroutines.CompletableDeferred<Int>()
        val output = StringBuilder()
        synchronized(lock) {
            check(!closed) { "Shizuku 终端会话已关闭" }
            check(pending == null) { "终端仍在执行上一条命令" }
            pending = Pending(marker, deferred, output)
            writer.write(text)
            writer.newLine()
            writer.write("printf '\\n${marker}%s\\n' \"\\$?\"")
            writer.newLine()
            writer.flush()
        }
        return try {
            CommandSession.CommandResult(deferred.await(), output.toString())
        } catch (cancelled: KotlinCancellationException) {
            close()
            throw cancelled
        }
    }

    override fun send(bytes: ByteArray) {
        synchronized(lock) {
            if (closed) return
            runCatching {
                writer.flush()
                outputStream.write(bytes)
                outputStream.flush()
            }
        }
    }

    override fun isAlive(): Boolean = !closed && runCatching { process.alive() }.getOrDefault(false)

    fun snapshot() = ShizukuSessionSnapshot(id, isAlive(), startedAt)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending?.deferred?.completeExceptionally(IOException("Shizuku 终端会话已关闭"))
            pending = null
            runCatching { writer.close() }
            runCatching { process.destroy() }
        }
        onClosed()
    }

    private fun read(fd: ParcelFileDescriptor, stderr: Boolean) {
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        val active = synchronized(lock) { pending }
                        if (!stderr && active != null && line.startsWith(active.marker)) {
                            val code = line.removePrefix(active.marker).trim().toIntOrNull() ?: 1
                            synchronized(lock) { if (pending === active) pending = null }
                            active.deferred.complete(code)
                        } else {
                            val visible = if (stderr) "[stderr] $line" else line
                            synchronized(lock) {
                                if (active != null && active.output.length < 12_000) {
                                    active.output.append(line).append('\n')
                                }
                            }
                            onOutput(visible)
                        }
                    }
                }
            } catch (_: Exception) {
                if (!closed) synchronized(lock) { pending?.deferred?.completeExceptionally(IOException("Shizuku 输出流已关闭")) }
            }
        }.apply {
            name = "hsucode-shizuku-${if (stderr) "stderr" else "stdout"}"
            isDaemon = true
            start()
        }
    }

    private data class Pending(val marker: String, val deferred: kotlinx.coroutines.CompletableDeferred<Int>, val output: StringBuilder)
}

private class ManagedShizukuProcess(
    private val id: String,
    private val command: String,
    private val process: IRemoteProcess,
    private val label: String,
    private val onChanged: () -> Unit,
) {
    private val startedAt = System.currentTimeMillis()
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()
    @Volatile private var exitCode: Int? = null

    init {
        capture(process.getInputStream(), stdout)
        capture(process.getErrorStream(), stderr)
        Thread {
            exitCode = runCatching { process.waitFor() }.getOrElse { -1 }
            onChanged()
        }.apply { name = "hsucode-shizuku-process-$id"; isDaemon = true; start() }
    }

    fun stop() { runCatching { process.destroy() }; onChanged() }

    fun snapshot(): ShizukuProcessSnapshot = ShizukuProcessSnapshot(
        id, "$label: $command", exitCode == null && runCatching { process.alive() }.getOrDefault(false),
        exitCode, synchronized(stdout) { stdout.toString().takeLast(16_000) },
        synchronized(stderr) { stderr.toString().takeLast(16_000) }, startedAt
    )

    private fun capture(fd: ParcelFileDescriptor, target: StringBuilder) {
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> synchronized(target) {
                        if (target.length < 16_000) target.append(line).append('\n')
                    } }
                }
            }
            onChanged()
        }.apply { name = "hsucode-shizuku-capture-$id"; isDaemon = true; start() }
    }
}
