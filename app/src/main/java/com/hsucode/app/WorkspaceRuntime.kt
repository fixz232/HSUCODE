package com.hsucode.app

import com.hsucode.app.root.ExecResult
import com.hsucode.app.root.RootShellManager
import com.hsucode.app.root.RootStatus

/** Selects one consistent command backend for the terminal, agents, and environment tools. */
object WorkspaceRuntime {
    enum class Backend { PROOT_UBUNTU, ROOT_CHROOT, ANDROID_SHELL }

    @Volatile
    private var rootModeEnabled = false

    /** Root is opt-in; the default backend is always the rootless PRoot workspace. */
    fun setRootModeEnabled(enabled: Boolean) {
        rootModeEnabled = enabled
    }

    fun isRootModeEnabled(): Boolean = rootModeEnabled

    fun backend(): Backend = when {
        rootModeEnabled && RootShellManager.rootStatus == RootStatus.OK && LinuxEnvironment.isReady() -> Backend.ROOT_CHROOT
        ProotLinuxEnvironment.isReady() -> Backend.PROOT_UBUNTU
        else -> Backend.ANDROID_SHELL
    }

    fun hasLinux(): Boolean = backend() != Backend.ANDROID_SHELL

    fun title(): String = when (backend()) {
        Backend.PROOT_UBUNTU -> "Ubuntu 终端"
        Backend.ROOT_CHROOT -> "Ubuntu 终端"
        Backend.ANDROID_SHELL -> "免 Root 工作区"
    }

    fun detail(): String = when (backend()) {
        Backend.PROOT_UBUNTU -> "PRoot Ubuntu · 无需 Root"
        Backend.ROOT_CHROOT -> "root + chroot · 增强环境"
        Backend.ANDROID_SHELL -> "Android Shell · 应用私有目录"
    }

    suspend fun runStreaming(command: String, onLine: (String) -> Unit): ExecResult = when (backend()) {
        Backend.PROOT_UBUNTU -> ProotLinuxEnvironment.runStreaming(command, onLine)
        Backend.ROOT_CHROOT -> LinuxEnvironment.runInEnvStreaming(command, onLine)
        Backend.ANDROID_SHELL -> UserWorkspaceShell.runStreaming(command, onLine)
    }

    internal fun startInteractiveSession(onLine: (String) -> Unit): InteractiveShellSession? = when (backend()) {
        Backend.PROOT_UBUNTU -> ProotLinuxEnvironment.startInteractiveSession(onLine)
        Backend.ANDROID_SHELL -> UserWorkspaceShell.startInteractiveSession(onLine)
        // Root chroot is deliberately kept on the existing verified libsu path.
        Backend.ROOT_CHROOT -> null
    }
}
