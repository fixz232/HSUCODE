package com.hsucode.app

import com.hsucode.app.root.ExecResult
import com.hsucode.app.root.RootShellManager
import com.hsucode.app.root.RootStatus

/** Selects one consistent command backend for the terminal, agents, and environment tools. */
object WorkspaceRuntime {
    enum class Backend { PROOT_UBUNTU, ROOT_CHROOT, SHIZUKU_SHELL, ANDROID_SHELL }

    @Volatile
    private var rootModeEnabled = false
    @Volatile
    private var shizukuTerminalEnabled = false

    /** Root is opt-in; the default backend is always the rootless PRoot workspace. */
    fun setRootModeEnabled(enabled: Boolean) {
        rootModeEnabled = enabled
    }

    fun isRootModeEnabled(): Boolean = rootModeEnabled

    fun setShizukuTerminalEnabled(enabled: Boolean) { shizukuTerminalEnabled = enabled }

    fun isShizukuTerminalEnabled(): Boolean = shizukuTerminalEnabled

    fun backend(): Backend = when {
        shizukuTerminalEnabled && ShizukuManager.state.value.usable -> Backend.SHIZUKU_SHELL
        rootModeEnabled && RootShellManager.rootStatus == RootStatus.OK && LinuxEnvironment.isReady() -> Backend.ROOT_CHROOT
        ProotLinuxEnvironment.isReady() -> Backend.PROOT_UBUNTU
        else -> Backend.ANDROID_SHELL
    }

    fun hasLinux(): Boolean = backend() in setOf(Backend.PROOT_UBUNTU, Backend.ROOT_CHROOT)

    fun title(): String = when (backend()) {
        Backend.PROOT_UBUNTU -> "Ubuntu 终端"
        Backend.ROOT_CHROOT -> "Ubuntu 终端"
        Backend.SHIZUKU_SHELL -> "Shizuku 终端"
        Backend.ANDROID_SHELL -> "免 Root 工作区"
    }

    fun detail(): String = when (backend()) {
        Backend.PROOT_UBUNTU -> "PRoot Ubuntu · 无需 Root"
        Backend.ROOT_CHROOT -> "root + chroot · 增强环境"
        Backend.SHIZUKU_SHELL -> "Shizuku · shell UID 2000 / root 后端"
        Backend.ANDROID_SHELL -> "Android Shell · 应用私有目录"
    }

    suspend fun runStreaming(command: String, onLine: (String) -> Unit): ExecResult = when (backend()) {
        Backend.PROOT_UBUNTU -> ProotLinuxEnvironment.runStreaming(command, onLine)
        Backend.ROOT_CHROOT -> LinuxEnvironment.runInEnvStreaming(command, onLine)
        Backend.SHIZUKU_SHELL -> {
            val started = System.currentTimeMillis()
            val result = ShizukuManager.executeStreaming(command) { text, stderr ->
                if (stderr) onLine("[stderr] ${text.trimEnd()}") else onLine(text.trimEnd())
            }
            ExecResult(result.stdout, result.stderr, result.exitCode, System.currentTimeMillis() - started, result.exitCode == 0)
        }
        Backend.ANDROID_SHELL -> UserWorkspaceShell.runStreaming(command, onLine)
    }

    internal fun startInteractiveSession(onLine: (String) -> Unit): CommandSession? = when (backend()) {
        Backend.PROOT_UBUNTU -> ProotLinuxEnvironment.startInteractiveSession(onLine)
        Backend.SHIZUKU_SHELL -> ShizukuManager.startInteractiveSession(onOutput = onLine)
        Backend.ANDROID_SHELL -> UserWorkspaceShell.startInteractiveSession(onLine)
        // Root chroot is deliberately kept on the existing verified libsu path.
        Backend.ROOT_CHROOT -> null
    }
}
