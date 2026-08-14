package com.hsucode.tools

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded text access plus same-directory replacement for agent-owned workspace files. */
object SafeWorkspaceFiles {
    const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    private const val MAX_WRITE_CHARS = 4 * 1024 * 1024

    fun readEditableText(file: File): String {
        require(file.length() <= MAX_EDIT_BYTES) {
            "文件超过 ${MAX_EDIT_BYTES / 1024 / 1024} MB，不能进行整文件编辑；请使用按行读取或终端。"
        }
        return file.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    fun writeTextAtomically(file: File, content: String) {
        require(content.length <= MAX_WRITE_CHARS) { "写入内容超过 4 MB 限制" }
        val partial = createPartial(file)
        try {
            partial.bufferedWriter(StandardCharsets.UTF_8).use { it.write(content) }
            commit(partial, file)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun createPartial(destination: File): File {
        destination.parentFile?.let { parent ->
            require(parent.exists() || parent.mkdirs()) { "无法创建目录: ${parent.path}" }
        }
        return File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.partial")
    }

    fun commit(partial: File, destination: File) {
        try {
            Files.move(
                partial.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
