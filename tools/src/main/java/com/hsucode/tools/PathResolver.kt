package com.hsucode.tools

import java.io.File

/** Resolves tool paths inside the active workspace, including symlink traversal checks. */
object PathResolver {
    /** 当前工作区根:随 [WorkspaceContext.workspaceRoot](用户可在设置/项目里改)动态变化。 */
    val WORKSPACE_ROOT: String get() = WorkspaceContext.workspaceRoot

    /** Resolve [raw] to a canonical path, or null when it escapes the workspace. */
    fun resolve(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val root = File(WORKSPACE_ROOT).canonicalFile
            val candidate = if (File(raw).isAbsolute) File(raw) else File(root, raw)
            val canonical = candidate.canonicalFile
            val rootPath = root.path.trimEnd(File.separatorChar)
            if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) {
                canonical.path
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
