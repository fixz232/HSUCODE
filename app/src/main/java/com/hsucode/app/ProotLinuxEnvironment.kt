package com.hsucode.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hsucode.app.root.ExecResult
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * A rootless Ubuntu workspace powered by the PRoot launcher shipped with the app.
 *
 * The rootfs and all temporary data stay inside [Context.filesDir]. PRoot only maps
 * the app-private [UserWorkspaceShell] directory into `/workspace`; it never asks
 * for `su`, storage permission, or a device-wide mount.
 */
object ProotLinuxEnvironment {
    private const val TAG = "ProotLinux"
    private const val ROOT_DIR_NAME = "proot-linux"
    private const val ROOTFS_DIR_NAME = "rootfs"
    private const val READY_FILE = ".hsucode_proot_ready"
    private const val PROOT_EXEC = "libproot_exec.so"
    private const val PROOT_LOADER = "libproot_loader.so"
    private const val MAX_LOG_CHARS = 4_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val TAR_BLOCK_SIZE = 512

    enum class State { NOT_SETUP, SETTING_UP, READY, ERROR, UNSUPPORTED }

    var state by mutableStateOf(State.NOT_SETUP)
        private set
    var setupLog by mutableStateOf("")
        private set

    @Volatile
    var outputSink: ((String) -> Unit)? = null

    private var appContext: Context? = null
    private var installRoot: File? = null
    private var nativeLibraryDir: File? = null
    private var activeSource: RootfsSource? = null
    private val installing = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        installRoot = File(context.filesDir, ROOT_DIR_NAME).apply { mkdirs() }
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        activeSource = rootfsSourceFor(Build.SUPPORTED_ABIS.asList())
        state = when {
            activeSource == null || !hasNativeLauncher() -> State.UNSUPPORTED
            hasUsableRootfs() -> State.READY
            else -> State.NOT_SETUP
        }
    }

    fun isReady(): Boolean = state == State.READY && hasUsableRootfs() && hasNativeLauncher()

    fun isSupported(): Boolean = activeSource != null && hasNativeLauncher()

    fun supportDetail(): String = when {
        activeSource == null -> "当前设备 ABI 不受支持，仅支持 arm64-v8a 与 x86_64。"
        !hasNativeLauncher() -> "PRoot 启动组件不可用，请重新安装 HSUCODE。"
        else -> "支持免 Root Ubuntu 工作区。"
    }

    fun displayPath(): String = rootfsDir()?.absolutePath ?: "免 Root Ubuntu 初始化中"

    fun cancelBootstrap() {
        if (state == State.SETTING_UP) {
            cancelRequested.set(true)
            log("已请求取消，正在停止当前下载或解压…")
        }
    }

    /** Installs or refreshes Ubuntu without deleting a working rootfs until staging succeeds. */
    suspend fun bootstrap(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!installing.compareAndSet(false, true)) return@withContext false
        try {
            val root = installRoot ?: return@withContext false
            val source = activeSource
            if (source == null || !hasNativeLauncher()) {
                state = State.UNSUPPORTED
                return@withContext false
            }

            state = State.SETTING_UP
            setupLog = ""
            cancelRequested.set(false)
            val rootfs = rootfsDir() ?: run {
                state = State.ERROR
                log("部署失败：无法创建 Ubuntu 文件系统目录")
                return@withContext false
            }
            val temp = File(root, "tmp").apply { mkdirs() }
            val archive = File(temp, source.fileName)
            val staging = File(root, "rootfs-installing")
            val backup = File(root, "rootfs-previous")
            try {
            if (!force && hasUsableRootfs()) {
                log("检测到已安装的免 Root Ubuntu，跳过重复部署。")
                state = State.READY
                return@withContext true
            }
            log("准备下载 Ubuntu 24.04 基础系统（免 Root）…")
            staging.deleteRecursively()
            staging.mkdirs()
            downloadVerifiedRootfs(source, archive)
            ensureNotCancelled()
            log("校验通过，正在安全解压 Ubuntu 文件系统…")
            extractTarGz(archive, staging)
            ensureNotCancelled()
            require(File(staging, "bin/sh").exists()) { "Rootfs 解压不完整，缺少 /bin/sh" }
            patchRootfs(staging)
            replaceRootfs(rootfs, staging, backup)
            File(root, READY_FILE).writeText("${source.fileName}\n")
            state = State.READY
            log("免 Root Ubuntu 已就绪 ✓")
            true
        } catch (cancelled: InstallCancelledException) {
            log("已取消免 Root Ubuntu 安装。现有环境未被修改。")
            state = if (hasUsableRootfs()) State.READY else State.NOT_SETUP
            false
        } catch (error: Exception) {
            Log.w(TAG, "bootstrap failed", error)
            log("部署失败：${error.message?.take(180) ?: "未知错误"}")
            state = if (hasUsableRootfs()) State.READY else State.ERROR
            false
        } finally {
            archive.delete()
            staging.deleteRecursively()
            if (backup.exists() && hasUsableRootfs()) backup.deleteRecursively()
            }
        } finally {
            // Every return above, including unsupported/missing-rootfs paths, must release this lock.
            installing.set(false)
        }
    }

    /** Executes a command in the PRoot guest. Output is read continuously to avoid pipe deadlocks. */
    suspend fun runStreaming(command: String, onLine: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext ExecResult("", "免 Root Ubuntu 尚未安装", 127, 0L, false)
        val rootfs = rootfsDir() ?: return@withContext ExecResult("", "找不到 Ubuntu rootfs", 127, 0L, false)
        val nativeDir = nativeLibraryDir ?: return@withContext ExecResult("", "找不到 PRoot 启动组件", 127, 0L, false)
        val proot = File(nativeDir, PROOT_EXEC)
        val loader = File(nativeDir, PROOT_LOADER)
        val workspaceRoot = UserWorkspaceShell.directory()
            ?: return@withContext ExecResult("", "工作区尚未初始化", 127, 0L, false)
        val workspace = UserWorkspaceShell.currentDirectory()
            ?: return@withContext ExecResult("", "cwd 不可用", 127, 0L, false)
        if (!workspaceRoot.isDirectory || !workspace.isDirectory) return@withContext ExecResult("", "工作区目录不可用", 127, 0L, false)

        val start = System.currentTimeMillis()
        var process: Process? = null
        try {
            patchRootfs(rootfs)
            process = ProcessBuilder(buildCommand(proot, rootfs, workspaceRoot, command))
                .directory(workspace)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = File(installRoot, "tmp").absolutePath
                    environment()["TMPDIR"] = File(installRoot, "tmp").absolutePath
                }
                .start()
            val activeProcess = process
            val reader = Thread {
                try {
                    activeProcess.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
                } catch (_: IOException) {
                    // The stream is expected to close when a timed-out process is terminated.
                }
            }.apply {
                name = "hsucode-proot-output"
                isDaemon = true
                start()
            }
            val timeoutSeconds = WorkspaceCommandPolicy.timeoutSeconds(command)
            val complete = activeProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!complete) {
                onLine("[超时] 命令超过 ${timeoutSeconds}s，已停止")
                activeProcess.destroyForcibly()
            }
            reader.join(1_500)
            val exitCode = if (complete) activeProcess.exitValue() else 124
            ExecResult("", "", exitCode, System.currentTimeMillis() - start, complete && exitCode == 0)
        } catch (error: Exception) {
            process?.destroyForcibly()
            ExecResult("", error.message.orEmpty(), -1, System.currentTimeMillis() - start, false)
        }
    }

    /** Starts one persistent bash process inside the same isolated guest used by runStreaming. */
    internal fun startInteractiveSession(onLine: (String) -> Unit): InteractiveShellSession? {
        if (!isReady()) return null
        val rootfs = rootfsDir() ?: return null
        val nativeDir = nativeLibraryDir ?: return null
        val workspaceRoot = UserWorkspaceShell.directory() ?: return null
        val workspace = UserWorkspaceShell.currentDirectory() ?: return null
        val proot = File(nativeDir, PROOT_EXEC)
        val loader = File(nativeDir, PROOT_LOADER)
        if (!proot.isFile || !loader.isFile || !workspace.isDirectory) return null
        return runCatching {
            patchRootfs(rootfs)
            ProcessBuilder(buildInteractiveCommand(proot, rootfs, workspaceRoot)).apply {
                directory(workspace)
                redirectErrorStream(true)
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = File(requireNotNull(installRoot), "tmp").absolutePath
                environment()["TMPDIR"] = File(requireNotNull(installRoot), "tmp").absolutePath
            }.start().let { InteractiveShellSession(it, onLine) }
        }.getOrNull()
    }

    /**
     * Patches the guest before a real Termux PTY is created. This is kept
     * separate from session creation because TerminalSession must be built on
     * the main thread while rootfs I/O must stay off it.
     */
    internal fun preparePtyTerminal(): Boolean {
        if (!isReady()) return false
        val rootfs = rootfsDir() ?: return false
        return runCatching {
            patchRootfs(rootfs)
            File(installRoot ?: return@runCatching false, "tmp").mkdirs()
            true
        }.getOrDefault(false)
    }

    /** Creates a Termux TerminalSession backed by the same isolated PRoot guest. */
    internal fun createPtyTerminalSession(client: TerminalSessionClient): TerminalSession? {
        if (!isReady()) return null
        val rootfs = rootfsDir() ?: return null
        val nativeDir = nativeLibraryDir ?: return null
        val root = installRoot ?: return null
        val workspace = UserWorkspaceShell.directory() ?: return null
        val proot = File(nativeDir, PROOT_EXEC)
        val loader = File(nativeDir, PROOT_LOADER)
        if (!proot.isFile || !loader.isFile || !workspace.isDirectory) return null
        val args = buildInteractiveCommand(proot, rootfs, workspace).drop(1)
        val env = arrayOf(
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${File(root, "tmp").absolutePath}",
            "TMPDIR=${File(root, "tmp").absolutePath}",
        )
        return runCatching {
            TerminalSession(
                proot.absolutePath,
                workspace.absolutePath,
                args.toTypedArray(),
                env,
                2_000,
                client,
            ).apply {
                mSessionName = "HSUCODE Ubuntu"
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to create Termux PTY session", error)
        }.getOrNull()
    }

    /**
     * Starts a long-lived stdio MCP process inside the verified PRoot guest.
     * Unlike [runStreaming], the caller owns stdin/stdout and therefore can perform MCP JSON-RPC.
     */
    fun startMcpProcess(command: String, args: List<String>, env: Map<String, String>): Process {
        check(isReady()) { "免 Root Ubuntu 尚未就绪" }
        require(command.isNotBlank()) { "MCP command 不能为空" }
        val root = requireNotNull(installRoot) { "找不到 Ubuntu 目录" }
        val nativeDir = requireNotNull(nativeLibraryDir) { "找不到 PRoot 启动组件" }
        val rootfs = requireNotNull(rootfsDir()) { "找不到 Ubuntu 根文件系统" }
        val workspace = requireNotNull(UserWorkspaceShell.directory()) { "工作区尚未初始化" }
        val proot = File(nativeDir, PROOT_EXEC)
        val loader = File(nativeDir, PROOT_LOADER)
        require(proot.isFile && loader.isFile) { "PRoot 启动组件不可用" }
        val guestCommand = buildMcpCommand(command, args, env)
        return ProcessBuilder(buildCommand(proot, rootfs, workspace, guestCommand)).apply {
            redirectErrorStream(false)
            environment()["PROOT_LOADER"] = loader.absolutePath
            environment()["PROOT_TMP_DIR"] = File(root, "tmp").absolutePath
            environment()["TMPDIR"] = File(root, "tmp").absolutePath
        }.start()
    }

    /** Removes only the Linux guest; the shared `/workspace` files are intentionally retained. */
    suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        val root = installRoot ?: return@withContext true
        cancelBootstrap()
        File(root, READY_FILE).delete()
        rootfsDir()?.deleteRecursively()
        File(root, "rootfs-installing").deleteRecursively()
        File(root, "rootfs-previous").deleteRecursively()
        state = if (isSupported()) State.NOT_SETUP else State.UNSUPPORTED
        setupLog = ""
        true
    }

    private suspend fun downloadVerifiedRootfs(source: RootfsSource, archive: File) {
        val expected = fetchChecksum(source)
            ?: error("无法取得 Ubuntu 官方 SHA-256 校验清单，已停止安装以保护工作区。")
        for (url in source.urls) {
            ensureNotCancelled()
            archive.delete()
            log("下载：${url.substringAfter("//").substringBefore('/')}")
            if (!download(url, archive)) continue
            if (!isGzip(archive)) {
                log("下载内容不是 gzip 压缩包，正在切换镜像…")
                continue
            }
            val actual = sha256(archive)
            if (actual.equals(expected, ignoreCase = true)) return
            log("文件校验不匹配，正在切换镜像…")
        }
        error("所有 Ubuntu 下载源都失败或未通过 SHA-256 校验。")
    }

    private fun fetchChecksum(source: RootfsSource): String? {
        for (url in source.checksumUrls) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "HSUCODE/1.14").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val text = response.body?.string().orEmpty()
                    val match = CHECKSUM_LINE.findAll(text).firstOrNull { result ->
                        result.groupValues[2] == source.fileName
                    } ?: return@use
                    return match.groupValues[1].lowercase(Locale.US)
                }
            } catch (error: Exception) {
                Log.w(TAG, "checksum fetch failed: $url", error)
            }
        }
        return null
    }

    private suspend fun download(url: String, target: File): Boolean = try {
        val request = Request.Builder().url(url).header("User-Agent", "HSUCODE/1.14").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log("下载失败：HTTP ${response.code}")
                return false
            }
            val body = response.body ?: return false
            val total = body.contentLength().takeIf { it > 0 }
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    var lastReported = 0L
                    while (true) {
                        ensureNotCancelled()
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= 2L * 1024 * 1024 || copied == total) {
                            lastReported = copied
                            val suffix = total?.let { " / ${it / 1024 / 1024} MB" }.orEmpty()
                            log("已下载 ${copied / 1024 / 1024} MB$suffix")
                        }
                    }
                    copied > 1_024L
                }
            }
        }
    } catch (cancelled: InstallCancelledException) {
        throw cancelled
    } catch (error: Exception) {
        log("下载异常：${error.message?.take(120)}")
        false
    }

    private suspend fun extractTarGz(archive: File, staging: File) {
        GZIPInputStream(BufferedInputStream(archive.inputStream(), BUFFER_SIZE)).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                ensureNotCancelled()
                currentCoroutineContext().ensureActive()
                val raw = input.readTarHeader() ?: break
                val header = raw.copy(name = pendingName ?: raw.name, linkName = pendingLinkName ?: raw.linkName)
                pendingName = null
                pendingLinkName = null
                when (header.type) {
                    TarType.LONG_NAME -> {
                        pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        input.skipFully(header.size.paddingSize())
                        continue
                    }
                    TarType.LONG_LINK -> {
                        pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        input.skipFully(header.size.paddingSize())
                        continue
                    }
                    TarType.PAX -> {
                        val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                        pendingName = pax["path"]
                        pendingLinkName = pax["linkpath"]
                        input.skipFully(header.size.paddingSize())
                        continue
                    }
                    else -> Unit
                }
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                val target = staging.safeResolveTarPath(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarType.DIRECTORY -> target.mkdirs()
                    TarType.FILE -> target.outputStream().use { output -> input.copyExactly(output, header.size) }
                    TarType.SYMLINK -> createSymbolicLink(target, header.linkName)
                    TarType.HARDLINK -> createHardLink(staging, target, header.linkName)
                    TarType.OTHER -> input.skipFully(header.size)
                    TarType.LONG_NAME, TarType.LONG_LINK, TarType.PAX -> Unit
                }
                input.skipFully(header.size.paddingSize())
                if (header.type != TarType.SYMLINK) applyMode(target, header.mode)
                if (header.modTime > 0 && header.type != TarType.SYMLINK) target.setLastModified(header.modTime * 1_000)
                entries++
                if (entries % 120 == 0) log("已解压 $entries 个文件：${header.name.takeLast(70)}")
            }
            log("已安全解压 $entries 个文件。")
        }
    }

    private fun replaceRootfs(rootfs: File, staging: File, backup: File) {
        backup.deleteRecursively()
        if (rootfs.exists()) require(rootfs.renameTo(backup)) { "无法保留现有 Ubuntu 环境" }
        try {
            require(staging.renameTo(rootfs)) { "无法启用新的 Ubuntu 环境" }
        } catch (error: Exception) {
            if (!rootfs.exists() && backup.exists()) backup.renameTo(rootfs)
            throw error
        }
        backup.deleteRecursively()
    }

    private fun buildBaseCommand(proot: File, rootfs: File, workspace: File): MutableList<String> {
        val linux = UserWorkspaceShell.linuxDirectory() ?: File(workspace.parentFile, "linux")
        val tmp = UserWorkspaceShell.tmpDirectory() ?: File(workspace.parentFile, "tmp")
        return mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-w", WorkspaceManager.guestCwd(),
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "${linux.absolutePath}:/root",
            "-b", "${tmp.absolutePath}:/tmp",
            "-b", "${tmp.absolutePath}:/var/tmp",
        ).also { result ->
            listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach { path ->
                result += "-b"
                result += path
            }
        }
    }

    private fun buildCommand(proot: File, rootfs: File, workspace: File, command: String): List<String> {
        val result = buildBaseCommand(proot, rootfs, workspace)
        result += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash", "-l", "-c",
            "cd -- \"\$1\" && eval \"\$2\"",
            "hsucode", WorkspaceManager.guestCwd(), command,
        )
        return result
    }

    private fun buildInteractiveCommand(proot: File, rootfs: File, workspace: File): List<String> {
        val result = buildBaseCommand(proot, rootfs, workspace)
        result += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "USER=root",
            "SHELL=/bin/bash",
            "PWD=${WorkspaceManager.guestCwd()}",
            "PS1=root@localhost:/workspace# ",
            "/bin/bash", "--noprofile", "--norc"
        )
        return result
    }

    private fun buildMcpCommand(command: String, args: List<String>, env: Map<String, String>): String {
        val assignments = env.entries
            .filter { (key, _) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            .joinToString(" ") { (key, value) -> "$key=${shellQuote(value)}" }
        val invocation = (listOf(command) + args).joinToString(" ") { shellQuote(it) }
        return listOf(assignments, "exec", invocation).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\\"'\\\"'") + "'"

    private fun patchRootfs(rootfs: File) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) return
        val resolv = File(etc, "resolv.conf")
        // Ubuntu base commonly ships this as a dangling /run/systemd symlink. File.exists()
        // returns false for that case, so use lstat and remove the link itself before writing.
        if (runCatching { Os.lstat(resolv.absolutePath); true }.getOrDefault(false)) {
            require(resolv.delete()) { "Unable to replace rootfs resolv.conf" }
        }
        resolv.writeText("# Generated by HSUCODE PRoot workspace.\nnameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val hosts = File(etc, "hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost hsucode\n::1 localhost ip6-localhost ip6-loopback\n")
        val hostname = File(etc, "hostname")
        if (!hostname.exists() || hostname.readText().trim().isBlank()) hostname.writeText("hsucode\n")
        patchAptSources(rootfs)
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "var/tmp").mkdirs()
        File(rootfs, "root").mkdirs()
        listOf(File(rootfs, "tmp"), File(rootfs, "var/tmp")).forEach { dir ->
            runCatching { Os.chmod(dir.absolutePath, 0b111_111_111) }
        }
    }

    /**
     * Ubuntu base images can point at a distant official archive. Replace just
     * the archive host with the corresponding Ubuntu mirror before the first
     * apt command, keeping the image's existing suites and signing settings.
     */
    private fun patchAptSources(rootfs: File) {
        val arch = ProotInstallSafety.selectSupportedAbi(Build.SUPPORTED_ABIS.asList()) ?: return
        val mirror = if (arch == "arm64-v8a") {
            "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
        } else {
            "http://mirrors.tuna.tsinghua.edu.cn/ubuntu"
        }
        val apt = File(rootfs, "etc/apt")
        if (!apt.isDirectory) return
        val candidates = listOf(
            File(apt, "sources.list"),
            File(apt, "sources.list.d/ubuntu.sources")
        ).filter { it.isFile }
        var replaced = false
        candidates.forEach { source ->
            val original = runCatching { source.readText() }.getOrNull() ?: return@forEach
            val patched = original
                .replace(Regex("https?://ports\\.ubuntu\\.com/ubuntu-ports"), mirror)
                .replace(Regex("https?://(archive|security)\\.ubuntu\\.com/ubuntu"), mirror)
            if (patched != original) {
                runCatching { source.writeText(patched) }
                replaced = true
            }
        }
        if (candidates.isEmpty()) {
            val list = File(apt, "sources.list")
            val codename = File(rootfs, "etc/os-release").takeIf { it.isFile }
                ?.readLines()?.firstOrNull { it.startsWith("VERSION_CODENAME=") }
                ?.substringAfter('=')?.trim('"')?.ifBlank { "noble" } ?: "noble"
            runCatching {
                list.writeText(
                    "deb $mirror $codename main restricted universe multiverse\n" +
                        "deb $mirror $codename-updates main restricted universe multiverse\n" +
                        "deb $mirror $codename-security main restricted universe multiverse\n"
                )
            }
        } else if (replaced) {
            log("已配置 Ubuntu 软件源镜像。")
        }
    }

    private fun hasUsableRootfs(): Boolean {
        val root = installRoot ?: return false
        val rootfs = rootfsDir() ?: return false
        return File(root, READY_FILE).isFile && rootfs.isDirectory && File(rootfs, "bin/sh").exists()
    }

    private fun hasNativeLauncher(): Boolean {
        val dir = nativeLibraryDir ?: return false
        return File(dir, PROOT_EXEC).isFile && File(dir, PROOT_LOADER).isFile
    }

    private fun rootfsDir(): File? = installRoot?.let { File(it, ROOTFS_DIR_NAME) }

    private fun log(line: String) {
        Log.i(TAG, line)
        setupLog = (setupLog + line + '\n').takeLast(MAX_LOG_CHARS)
        runCatching { outputSink?.invoke(line) }
    }

    private fun ensureNotCancelled() {
        if (cancelRequested.get()) throw InstallCancelledException()
    }

    private fun isGzip(file: File): Boolean = runCatching {
        file.inputStream().use { it.read() == 0x1F && it.read() == 0x8B }
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Tar header truncated")
        if (header.all { it == 0.toByte() }) return null
        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        return TarHeader(
            name = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/"),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarType.FILE
                '1' -> TarType.HARDLINK
                '2' -> TarType.SYMLINK
                '5' -> TarType.DIRECTORY
                'L' -> TarType.LONG_NAME
                'K' -> TarType.LONG_LINK
                'x' -> TarType.PAX
                else -> TarType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            ensureNotCancelled()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Tar file data truncated")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(size: Long): ByteArray {
        require(size <= 4 * 1024 * 1024) { "Tar metadata entry is too large" }
        val result = ByteArray(size.toInt())
        if (readFullyOrEnd(result) != result.size) throw EOFException("Tar metadata truncated")
        return result
    }

    private fun InputStream.skipFully(size: Long) {
        var remaining = size
        while (remaining > 0) {
            ensureNotCancelled()
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() >= 0) remaining--
            else throw EOFException("Tar data truncated")
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolveTarPath(entry: String): File {
        val normalized = ProotInstallSafety.normalizeTarPath(entry)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes destination: $entry"
        }
        return target
    }

    private fun createSymbolicLink(target: File, linkName: String) {
        if (linkName.isBlank()) return
        target.delete()
        runCatching { Os.symlink(linkName, target.absolutePath) }.getOrElse { error ->
            throw IOException("Unable to create rootfs symlink ${target.name}", error)
        }
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolveTarPath(linkName)
        if (!source.isFile) return
        target.delete()
        runCatching { Os.link(source.absolutePath, target.absolutePath) }
            .recoverCatching { source.copyTo(target, overwrite = true) }
            .getOrThrow()
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode == 0) return
        runCatching { Os.chmod(file.absolutePath, mode and 0b111_111_111) }
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val size = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + size).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            index += size
        }
        return result
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: offset + length
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val text = string(offset, length).trim()
        return if (text.isBlank()) 0L else text.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - this % TAR_BLOCK_SIZE).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0 else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class RootfsSource(
        val fileName: String,
        val urls: List<String>,
        val checksumUrls: List<String>,
    )

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarType,
        val linkName: String,
    )

    private enum class TarType { FILE, DIRECTORY, SYMLINK, HARDLINK, LONG_NAME, LONG_LINK, PAX, OTHER }

    private class InstallCancelledException : IOException("Rootfs installation cancelled")

    private fun rootfsSourceFor(abis: List<String>): RootfsSource? {
        val abi = ProotInstallSafety.selectSupportedAbi(abis) ?: return null
        val architecture = if (abi == "arm64-v8a") "arm64" else "amd64"
        val name = "ubuntu-base-24.04.3-base-$architecture.tar.gz"
        val release = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
        return RootfsSource(
            fileName = name,
            urls = listOf(
                "$release/$name",
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$name",
                "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$name",
            ),
            checksumUrls = listOf(
                "$release/SHA256SUMS",
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/SHA256SUMS",
            ),
        )
    }

    private val CHECKSUM_LINE = Regex("(?im)^([a-f0-9]{64})\\s+\\*?([^\\r\\n]+)$")
}

/** Small pure helpers kept separate so archive and ABI safety stay unit-testable. */
internal object ProotInstallSafety {
    fun selectSupportedAbi(abis: List<String>): String? = abis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }

    fun normalizeTarPath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trimStart('/').removePrefix("./")
        require(normalized.isNotBlank()) { "Rootfs entry path is blank" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes destination: $path" }
        return normalized
    }
}
