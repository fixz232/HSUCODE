package com.hsucode.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「环境配置」内置开发环境/工具的目录 + 安装引擎。
 *
 * 运行在 [WorkspaceRuntime] 提供的 Ubuntu(apt)用户态里，默认无需 Root。检测与安装命令都在
 * 该环境内执行:检测用 `command -v`,安装统一走 apt / 语言官方安装器。环境未部署时,UI 先引导部署。
 */

/** 单个可安装项。 */
data class EnvTool(
    val id: String,
    val name: String,
    val desc: String,
    /** 环境内检测是否已安装:退出码 0 = 已安装。 */
    val detectCmd: String,
    /** 环境内安装命令(在 Ubuntu 里执行)。 */
    val installCmd: String,
    /** 首次配置默认勾选。大型/可选工具必须由用户明确选择。 */
    val selectedByDefault: Boolean = false
)

/** 一组同类工具。 */
data class EnvCategory(
    val title: String,
    val subtitle: String,
    val required: Boolean,
    val tools: List<EnvTool>
)

object EnvCatalog {
    val categories: List<EnvCategory> = listOf(
        EnvCategory("Node.js 环境", "Node.js 和前端开发环境", required = true, tools = listOf(
            EnvTool("node", "Node.js", "JavaScript 运行时",
                "command -v node", "apt-get install -y nodejs npm", selectedByDefault = true),
            EnvTool("pnpm", "PNPM", "快速的包管理器和 TypeScript",
                "command -v pnpm || (command -v npm >/dev/null 2>&1 && " +
                    "test -f \"\$(npm root -g 2>/dev/null)/pnpm/bin/pnpm.cjs\")",
                "apt-get install -y nodejs npm && " +
                    "((npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 && " +
                    "npm install -g --no-fund --no-audit pnpm typescript) || " +
                    "(npm config delete registry >/dev/null 2>&1 || true; " +
                    "npm install -g --no-fund --no-audit pnpm typescript)) && " +
                    "mkdir -p /usr/local/bin && " +
                    "test -f \"\$(npm root -g 2>/dev/null)/pnpm/bin/pnpm.cjs\" && " +
                    "ln -sf \"\$(npm root -g 2>/dev/null)/pnpm/bin/pnpm.cjs\" /usr/local/bin/pnpm")
        )),
        EnvCategory("Python 环境", "Python 开发环境", required = true, tools = listOf(
            EnvTool("python_link", "Python 链接", "将 python 命令链接到 python3",
                "command -v python",
                "apt-get install -y python3 && ln -sf \"\$(command -v python3)\" /usr/local/bin/python", selectedByDefault = true),
            EnvTool("venv", "虚拟环境", "Python 虚拟环境支持",
                "python3 -c 'import ensurepip' >/dev/null 2>&1", "apt-get install -y python3-venv", selectedByDefault = true),
            EnvTool("pip", "Pip", "Python 包管理器",
                "command -v pip3 || command -v pip", "apt-get install -y python3-pip", selectedByDefault = true),
            EnvTool("uv", "uv", "一个用 Rust 编写的极速 Python 包安装器",
                "command -v uv",
                // Ubuntu 24.04 启用 PEP 668，系统 pip 会拒绝直接写入 site-packages。
                // 优先使用发行版包，缺包时安装到独立 venv，避免 --break-system-packages 污染系统 Python。
                "(apt-get install -y uv) || (apt-get install -y python3 python3-venv python3-pip && " +
                    "rm -rf /opt/hsucode-uv && python3 -m venv /opt/hsucode-uv && " +
                    "/opt/hsucode-uv/bin/python -m pip install --no-cache-dir -U " +
                    "-i https://pypi.tuna.tsinghua.edu.cn/simple uv && " +
                    "ln -sf /opt/hsucode-uv/bin/uv /usr/local/bin/uv)")
        )),
        EnvCategory("SSH 工具", "SSH 客户端和密码认证工具", required = false, tools = listOf(
            EnvTool("ssh", "SSH 客户端", "SSH 连接客户端",
                "command -v ssh", "apt-get install -y openssh-client"),
            EnvTool("sshpass", "sshpass", "SSH 密码认证工具",
                "command -v sshpass", "apt-get install -y sshpass"),
            EnvTool("sshd", "OpenSSH 服务器", "用于反向隧道挂载本地文件系统",
                "command -v sshd", "apt-get install -y openssh-server")
        )),
        EnvCategory("Java 环境", "Java 开发环境", required = false, tools = listOf(
            EnvTool("jdk17", "OpenJDK 17", "Java 17 开发环境",
                "command -v java", "apt-get install -y openjdk-17-jdk-headless"),
            EnvTool("gradle", "Gradle", "现代化的构建自动化工具",
                "command -v gradle", "apt-get install -y gradle")
        )),
        EnvCategory("Rust (Cargo) 环境", "Rust 开发环境和包管理器", required = false, tools = listOf(
            EnvTool("rust", "Rust & Cargo", "通过 rustup 安装 Rust 工具链",
                "command -v cargo",
                // 走国内 rsproxy 镜像:rustup-init 脚本 + 工具链下载 + cargo crates 索引全部国内;失败回退 apt(国内源)。
                "(apt-get install -y curl ca-certificates && " +
                    "export RUSTUP_DIST_SERVER=https://rsproxy.cn && export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup && " +
                    "curl --proto '=https' --tlsv1.2 -sSf https://rsproxy.cn/rustup-init.sh | sh -s -- -y && " +
                    "mkdir -p /root/.cargo && printf '[source.crates-io]\\nreplace-with = \"rsproxy-sparse\"\\n" +
                    "[source.rsproxy-sparse]\\nregistry = \"sparse+https://rsproxy.cn/index/\"\\n" +
                    "[net]\\ngit-fetch-with-cli = true\\n' > /root/.cargo/config.toml && " +
                    "mkdir -p /usr/local/bin && " +
                    "ln -sf /root/.cargo/bin/cargo /usr/local/bin/cargo && " +
                    "ln -sf /root/.cargo/bin/rustc /usr/local/bin/rustc && " +
                    "ln -sf /root/.cargo/bin/rustup /usr/local/bin/rustup) || " +
                    "apt-get install -y rustc cargo")
        )),
        EnvCategory("Go 环境", "Go 语言开发环境", required = false, tools = listOf(
            EnvTool("go", "Go", "Go 编程语言",
                "command -v go", "apt-get install -y golang-go")
        )),
        EnvCategory("Android 构建", "编译 APK 用的 SDK / NDK(下载较大,约 2GB)", required = false, tools = listOf(
            EnvTool("android_ndk", "Android SDK/NDK", "编译 APK 的命令行工具、build-tools、平台与 NDK",
                "ls /opt/android-sdk/ndk 2>/dev/null | grep -q .",
                "apt-get install -y wget unzip openjdk-17-jdk-headless && mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp && " +
                    // 验证压缩包后再使用，避免镜像返回错误页时留下不可用的半成品。
                    "((wget -qO cmd.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip && " +
                    "unzip -tq cmd.zip) || (rm -f cmd.zip && " +
                    "wget -qO cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && " +
                    "unzip -tq cmd.zip)) && " +
                    "rm -rf /opt/android-sdk/cmdline-tools/latest /opt/android-sdk/cmdline-tools/cmdline-tools && " +
                    "unzip -oq cmd.zip -d /opt/android-sdk/cmdline-tools && " +
                    "test -x /opt/android-sdk/cmdline-tools/cmdline-tools/bin/sdkmanager && " +
                    "mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && " +
                    "export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk && " +
                    "yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk " +
                    "'platform-tools' 'build-tools;34.0.0' 'platforms;android-34' 'ndk;26.1.10909125' && " +
                    "printf 'export ANDROID_HOME=/opt/android-sdk\\nexport ANDROID_SDK_ROOT=/opt/android-sdk\\n" +
                    "export PATH=\$PATH:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin\\n' > /etc/profile.d/android.sh")
        ))
    )

    val allTools: List<EnvTool> get() = categories.flatMap { it.tools }
    val defaultToolIds: Set<String> get() = allTools.filter { it.selectedByDefault }.mapTo(linkedSetOf()) { it.id }
}

data class EnvCommandResult(val ok: Boolean, val detail: String)

/** Process-wide, persisted status for an environment configuration task. */
data class EnvSetupProgress(
    val running: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val message: String = "",
    val detail: String = "",
    val installedIds: Set<String> = emptySet()
)

/** Keeps the configuration result visible after leaving and returning to the screen. */
object EnvSetupProgressStore {
    private const val PREFS = "env_setup_progress"
    private const val RUNNING = "running"
    private const val COMPLETED = "completed"
    private const val TOTAL = "total"
    private const val MESSAGE = "message"
    private const val DETAIL = "detail"
    private const val INSTALLED_IDS = "installed_ids"

    private var initialized = false
    var progress by mutableStateOf(EnvSetupProgress())
        private set

    fun state(context: Context): EnvSetupProgress {
        if (!initialized) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(RUNNING, false)
            progress = EnvSetupProgress(
                running = wasRunning && EnvSetupManager.isInstalling(),
                completedCount = prefs.getInt(COMPLETED, 0),
                totalCount = prefs.getInt(TOTAL, 0),
                message = prefs.getString(MESSAGE, "").orEmpty(),
                detail = prefs.getString(DETAIL, "").orEmpty(),
                installedIds = prefs.getStringSet(INSTALLED_IDS, emptySet()).orEmpty().toSet()
            )
            initialized = true
        }
        return progress
    }

    fun save(context: Context, value: EnvSetupProgress) {
        progress = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(RUNNING, value.running)
            .putInt(COMPLETED, value.completedCount)
            .putInt(TOTAL, value.totalCount)
            .putString(MESSAGE, value.message)
            .putString(DETAIL, value.detail)
            .putStringSet(INSTALLED_IDS, value.installedIds)
            .apply()
    }
}

/** 安装引擎:在内置 Ubuntu 环境(root chroot)内检测/安装,状态经 UI 反馈。 */
object EnvSetupManager {
    private const val TAG = "EnvSetup"
    private const val MARKER_PREFIX = "__HSUCODE_ENV__"
    private const val APT_OPTIONS =
        "-o Dpkg::Use-Pty=0 -o Acquire::Retries=2 " +
            "-o Acquire::http::Timeout=25 -o Acquire::https::Timeout=25 "
    private const val APT_UPDATE_COMMAND =
        "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get " + APT_OPTIONS + "update"
    private val installInProgress = AtomicBoolean(false)
    @Volatile private var lastProbeError: String? = null

    fun isInstalling(): Boolean = installInProgress.get()

    fun beginInstall(): Boolean = installInProgress.compareAndSet(false, true)

    fun endInstall() {
        installInProgress.set(false)
    }

    fun probeError(): String? = lastProbeError

    /**
     * Runs all probes in one guest process. Starting PRoot for every card made the
     * original page look frozen and could take minutes on slower phones.
     */
    suspend fun inspectInstalled(tools: List<EnvTool>): Map<String, Boolean> {
        val result = ConcurrentHashMap(tools.associate { it.id to false })
        val seen = ConcurrentHashMap.newKeySet<String>()
        if (!WorkspaceRuntime.hasLinux() || tools.isEmpty()) {
            lastProbeError = if (tools.isEmpty()) null else "Linux 工作区尚未就绪"
            return emptyMap()
        }
        val command = buildString {
            tools.forEach { tool ->
                append("if ( ").append(tool.detectCmd).append(" ) >/dev/null 2>&1; then ")
                append("printf '").append(MARKER_PREFIX).append(tool.id).append("=1\\n'; ")
                append("else printf '").append(MARKER_PREFIX).append(tool.id).append("=0\\n'; fi; ")
            }
            append("true")
        }
        return try {
            val execution = WorkspaceRuntime.runStreaming(command) { line ->
                if (!line.startsWith(MARKER_PREFIX)) return@runStreaming
                val entry = line.removePrefix(MARKER_PREFIX).split('=', limit = 2)
                if (entry.size == 2 && result.containsKey(entry[0])) {
                    seen += entry[0]
                    result[entry[0]] = entry[1] == "1"
                }
            }
            if (!execution.success) {
                Log.w(TAG, "environment probe exited with ${execution.exitCode}")
                lastProbeError = "环境探测命令退出码 ${execution.exitCode}：${execution.stderr.takeLast(300)}"
                return emptyMap()
            }
            if (seen.size != tools.size) {
                Log.w(TAG, "environment probe returned ${seen.size}/${tools.size} tool markers")
                lastProbeError = "环境探测返回不完整(${seen.size}/${tools.size})，未覆盖已有安装状态"
                return emptyMap()
            }
            lastProbeError = null
            result.toMap()
        } catch (error: Exception) {
            Log.w(TAG, "batch environment probe failed", error)
            lastProbeError = "环境探测失败：${error.message ?: "未知错误"}"
            emptyMap()
        }
    }

    /** 环境内检测单个工具是否已安装(退出码 0)。Linux 环境未就绪时一律视为未安装。 */
    suspend fun isInstalled(tool: EnvTool): Boolean {
        return inspectInstalled(listOf(tool))[tool.id] == true
    }

    /** Updates package metadata once per user-started batch, with bounded network retries. */
    suspend fun preparePackageManager(): EnvCommandResult {
        if (!WorkspaceRuntime.hasLinux()) return EnvCommandResult(false, "Ubuntu 环境尚未部署或验证失败")
        return runWithTerminalLog("正在更新软件源…", APT_UPDATE_COMMAND)
    }

    /** 环境内安装单个工具;调用方须先调用 [preparePackageManager]。输出【流式】进可视终端。 */
    suspend fun install(tool: EnvTool): Pair<Boolean, String> {
        if (!WorkspaceRuntime.hasLinux()) return false to "Ubuntu 环境尚未部署"
        val recovery = if (tool.installCmd.contains("apt-get")) {
            "dpkg --configure -a >/dev/null 2>&1 || true; " +
                "apt-get $APT_OPTIONS-f install -y >/dev/null 2>&1 || true; "
        } else ""
        val installCommand = tool.installCmd.replace("apt-get", "apt-get $APT_OPTIONS")
        val command = "export DEBIAN_FRONTEND=noninteractive; $recovery$installCommand"
        var result = runWithTerminalLog("安装 ${tool.name}…", command)
        if (!result.ok && isRetryable(tool)) {
            result = runWithTerminalLog("重试安装 ${tool.name}…", command)
        }
        Log.i(TAG, "install ${tool.id}: ok=${result.ok}")
        return result.ok to result.detail
    }

    private fun isRetryable(tool: EnvTool): Boolean {
        val command = tool.installCmd.lowercase()
        return listOf("apt-get", "curl", "wget", "npm", "pip", "sdkmanager").any(command::contains)
    }

    private suspend fun runWithTerminalLog(headline: String, command: String): EnvCommandResult {
        return try {
            val sink = when (WorkspaceRuntime.backend()) {
                WorkspaceRuntime.Backend.PROOT_UBUNTU -> ProotLinuxEnvironment.outputSink
                WorkspaceRuntime.Backend.ROOT_CHROOT -> LinuxEnvironment.outputSink
                WorkspaceRuntime.Backend.SHIZUKU_SHELL -> null
                WorkspaceRuntime.Backend.ANDROID_SHELL -> null
            }
            sink?.invoke("\n$ $headline")
            val tail = StringBuilder()
            val execution = WorkspaceRuntime.runStreaming(command) { line ->
                sink?.invoke(line)
                tail.append(line).append('\n')
                if (tail.length > 4_000) tail.delete(0, tail.length - 2_000)
            }
            val detail = tail.toString().trim().takeLast(600)
                .ifBlank { execution.stderr.ifBlank { "命令退出码：${execution.exitCode}" } }
            EnvCommandResult(execution.exitCode == 0, detail)
        } catch (error: Exception) {
            Log.w(TAG, "environment command failed", error)
            EnvCommandResult(false, error.message ?: "未知错误")
        }
    }
}
