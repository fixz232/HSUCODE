package com.hsucode.app

import android.content.Context
import java.io.File

/** Explicit app-private workspace layout shared by Android Shell and PRoot. */
data class WorkspaceRoots(
    val base: File,
    val files: File,
    val linux: File,
    val tmp: File
)

object WorkspaceManager {
    private const val ROOT_NAME = "workspace"
    private const val PREFS = "workspace_runtime"
    private const val CWD_KEY = "cwd"

    @Volatile private var roots: WorkspaceRoots? = null
    @Volatile private var preferences: android.content.SharedPreferences? = null

    fun init(context: Context): WorkspaceRoots {
        val base = File(context.filesDir, ROOT_NAME)
        val files = File(base, "files")
        // Preserve the pre-v1 layout where project files lived directly below workspace/.
        if (!files.exists()) {
            files.mkdirs()
            base.listFiles().orEmpty()
                .filter { it.name !in setOf("files", "linux", "tmp") }
                .forEach { legacy -> legacy.renameTo(File(files, legacy.name)) }
        }
        val resolved = WorkspaceRoots(base, files, File(base, "linux"), File(base, "tmp"))
        listOf(resolved.base, resolved.files, resolved.linux, resolved.tmp).forEach { it.mkdirs() }
        roots = resolved
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        normalizeCwd()
        return resolved
    }

    fun current(): WorkspaceRoots? = roots
    fun filesRoot(): File? = roots?.files
    fun linuxRoot(): File? = roots?.linux
    fun tmpRoot(): File? = roots?.tmp
    fun relativeCwd(): String = preferences?.getString(CWD_KEY, "").orEmpty()
    fun currentDirectory(): File? = filesRoot()?.let { resolveRelative(it, relativeCwd()).getOrNull() }

    fun setCwd(relative: String): Result<File> = runCatching {
        val root = requireNotNull(filesRoot()) { "工作区尚未初始化" }.canonicalFile
        val normalized = relative.trim().replace('\\', '/').trim('/').let { if (it == ".") "" else it }
        val target = resolveRelative(root, normalized).getOrThrow()
        require(target.isDirectory) { "cwd 不是文件夹" }
        val canonicalRelative = runCatching {
            root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        }.getOrDefault(normalized).trim('/')
        preferences?.edit()?.putString(CWD_KEY, canonicalRelative)?.apply()
        target
    }

    fun guestCwd(): String = relativeCwd().takeIf { it.isNotBlank() }?.let { "/workspace/$it" } ?: "/workspace"

    fun describe(): String {
        val r = roots ?: return "工作区未初始化"
        return "files=${r.files.name} · linux=${r.linux.name} · tmp=${r.tmp.name} · cwd=${relativeCwd().ifBlank { "/" }}"
    }

    private fun normalizeCwd() {
        val root = filesRoot() ?: return
        if (resolveRelative(root, relativeCwd()).getOrNull()?.isDirectory != true) {
            preferences?.edit()?.putString(CWD_KEY, "")?.apply()
        }
    }

    private fun resolveRelative(root: File, relative: String): Result<File> = runCatching {
        val canonicalRoot = root.canonicalFile
        val target = if (relative.isBlank()) canonicalRoot else File(canonicalRoot, relative).canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "路径必须位于 files 工作区内"
        }
        target
    }
}
