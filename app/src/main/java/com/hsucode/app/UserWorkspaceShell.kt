package com.hsucode.app

import android.content.Context
import com.hsucode.app.root.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * This app-private directory is shared by the Android Shell fallback and PRoot Ubuntu `/workspace`.
 * Commands always run as the app UID, so neither backend can gain root privileges.
 */
object UserWorkspaceShell {
    @Volatile
    private var workspaceRoots: WorkspaceRoots? = null

    fun init(context: Context) {
        workspaceRoots = WorkspaceManager.init(context)
    }

    fun isReady(): Boolean = workspaceRoots?.files?.isDirectory == true

    fun displayPath(): String = workspaceRoots?.files?.absolutePath ?: "工作区初始化中"

    /** The same app-private directory is bind-mounted to `/workspace` inside PRoot. */
    fun directory(): File? = workspaceRoots?.files

    fun linuxDirectory(): File? = workspaceRoots?.linux

    fun tmpDirectory(): File? = workspaceRoots?.tmp

    fun currentDirectory(): File? = WorkspaceManager.currentDirectory()

    fun relativeCwd(): String = WorkspaceManager.relativeCwd()

    fun setCwd(relative: String): Result<File> = WorkspaceManager.setCwd(relative)

    internal fun startInteractiveSession(onLine: (String) -> Unit): InteractiveShellSession? {
        val directory = currentDirectory() ?: return null
        if (!directory.isDirectory) return null
        return runCatching {
            ProcessBuilder("/system/bin/sh")
                .directory(directory)
                .redirectErrorStream(true)
                .start()
                .let { InteractiveShellSession(it, onLine) }
        }.getOrNull()
    }

    suspend fun runStreaming(command: String, onLine: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val directory = currentDirectory()
            ?: return@withContext ExecResult("", "免 Root 工作区尚未初始化", 1, 0L, false)
        if (!directory.isDirectory) {
            return@withContext ExecResult("", "免 Root 工作区目录不可用", 1, 0L, false)
        }

        val start = System.currentTimeMillis()
        var process: Process? = null
        try {
            process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(directory)
                .redirectErrorStream(true)
                .start()
            val activeProcess = process
            val outputReader = Thread {
                activeProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(onLine)
                }
            }.apply {
                name = "hsucode-workspace-output"
                start()
            }

            val timeoutSeconds = WorkspaceCommandPolicy.timeoutSeconds(command)
            val completed = activeProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                onLine("[超时] 命令超过 ${timeoutSeconds}s，已停止")
                activeProcess.destroy()
                if (activeProcess.isAlive) activeProcess.destroyForcibly()
            }
            outputReader.join(1_000)
            val exitCode = if (completed) activeProcess.exitValue() else 124
            ExecResult("", "", exitCode, System.currentTimeMillis() - start, completed && exitCode == 0)
        } catch (error: Exception) {
            process?.destroyForcibly()
            ExecResult("", error.message.orEmpty(), -1, System.currentTimeMillis() - start, false)
        }
    }
}
