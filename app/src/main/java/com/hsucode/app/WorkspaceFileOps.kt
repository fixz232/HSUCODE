package com.hsucode.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * Filesystem operations used by the visible workspace. Every path is resolved below [root],
 * so UI actions cannot accidentally leave the active workspace.
 */
object WorkspaceFileOps {
    private const val MAX_TEXT_BYTES = 1_000_000L
    private const val MAX_ARCHIVE_ENTRIES = 2_000
    private const val MAX_UNZIPPED_BYTES = 256L * 1024L * 1024L
    private const val BUFFER_SIZE = 16 * 1024

    data class Entry(
        val file: File,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long
    )

    data class SearchHit(val relativePath: String, val line: Int, val preview: String)

    fun root(path: String): Result<File> = runCatching {
        require(path.isNotBlank()) { "工作区目录未配置" }
        File(path).canonicalFile.also { root ->
            if (!root.exists()) require(root.mkdirs() || root.isDirectory) { "无法创建工作区" }
            require(root.isDirectory) { "工作区不是目录" }
        }
    }

    fun resolve(root: File, relativePath: String): Result<File> = runCatching {
        val canonicalRoot = root.canonicalFile
        val raw = File(relativePath)
        val candidate = if (relativePath.isBlank()) canonicalRoot
        // Treat a slash-prefixed path as absolute even in host-side Windows tests.
        // Android paths are POSIX, and accepting it as a relative `tmp/...` path
        // would make callers observe different sandbox rules across platforms.
        else if (raw.isAbsolute || relativePath.replace('\\', '/').startsWith('/')) raw.canonicalFile
        else File(canonicalRoot, relativePath).canonicalFile
        require(candidate.path == canonicalRoot.path || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "路径必须位于工作区内"
        }
        candidate
    }

    fun list(root: File, relativePath: String = ""): Result<List<Entry>> = runCatching {
        val directory = resolve(root, relativePath).getOrThrow()
        require(directory.isDirectory) { "这不是文件夹" }
        directory.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file -> entry(root, file) }
    }

    fun readText(root: File, relativePath: String): Result<String> = runCatching {
        val file = resolve(root, relativePath).getOrThrow()
        require(file.isFile) { "这不是文件" }
        require(file.length() <= MAX_TEXT_BYTES) { "文件超过 1 MB，请使用终端或按行读取" }
        file.readText()
    }

    fun writeText(root: File, relativePath: String, content: String): Result<Unit> = runCatching {
        require(relativePath.isNotBlank()) { "文件名不能为空" }
        val file = resolve(root, relativePath).getOrThrow()
        require(!file.exists() || file.isFile) { "目标是文件夹" }
        atomicWriteText(file, content)
    }

    fun createDirectory(root: File, relativePath: String): Result<Unit> = runCatching {
        require(relativePath.isNotBlank()) { "文件夹名不能为空" }
        val directory = resolve(root, relativePath).getOrThrow()
        require(directory.mkdirs() || directory.isDirectory) { "无法创建文件夹" }
    }

    /** Same-directory replacement keeps an existing document intact if writing is interrupted. */
    private fun atomicWriteText(file: File, content: String) {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, ".${file.name}.${System.nanoTime()}.partial")
        try {
            partial.writeText(content)
            try {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun delete(root: File, relativePath: String): Result<Unit> = runCatching {
        val file = resolve(root, relativePath).getOrThrow()
        require(file.canonicalPath != root.canonicalPath) { "不能删除工作区根目录" }
        require(file.exists()) { "文件不存在" }
        require(file.deleteRecursively()) { "删除失败" }
    }

    fun move(root: File, sourceRelativePath: String, targetRelativePath: String): Result<File> = runCatching {
        val source = resolve(root, sourceRelativePath).getOrThrow()
        require(source.canonicalPath != root.canonicalPath && source.exists()) { "源文件不存在或不能移动工作区根目录" }
        val target = resolve(root, targetRelativePath).getOrThrow()
        require(target.canonicalPath != root.canonicalPath) { "目标文件名不能为空" }
        require(target.canonicalPath != source.canonicalPath &&
            !target.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
            "不能把文件夹移动到自身内部"
        }
        require(!target.exists()) { "目标已存在" }
        target.parentFile?.mkdirs()
        require(source.renameTo(target)) { "移动失败，请确认目标位于同一存储卷" }
        target
    }

    /** File-name glob search, with ** matching across directories. */
    fun glob(root: File, pattern: String, maxResults: Int = 200): Result<List<Entry>> = runCatching {
        require(pattern.isNotBlank()) { "请输入文件匹配模式" }
        val regex = globRegex(pattern.replace('\\', '/'))
        root.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it != root && regex.matcher(relative(root, it)).matches() }
            .take(maxResults.coerceIn(1, 1_000))
            .map { entry(root, it) }
            .toList()
    }

    fun grep(root: File, query: String, maxResults: Int = 100): Result<List<SearchHit>> =
        search(root, query, maxResults)

    fun importFile(root: File, targetRelativePath: String, input: InputStream, maxBytes: Long = MAX_TEXT_BYTES * 20): Result<Long> = runCatching {
        val target = resolve(root, targetRelativePath).getOrThrow()
        require(target.canonicalPath != root.canonicalPath && !target.exists()) { "目标文件已存在或无效" }
        target.parentFile?.mkdirs()
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        input.use { source ->
            FileOutputStream(target).use { output ->
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "导入文件超过大小限制" }
                    output.write(buffer, 0, count)
                }
            }
        }
        total
    }

    fun exportFile(root: File, sourceRelativePath: String, output: OutputStream): Result<Long> = runCatching {
        val source = resolve(root, sourceRelativePath).getOrThrow()
        require(source.isFile) { "请选择一个文件" }
        var total = 0L
        source.inputStream().use { input -> output.use { target ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                target.write(buffer, 0, count); total += count
            }
        } }
        total
    }

    fun search(root: File, query: String, maxResults: Int = 100): Result<List<SearchHit>> = runCatching {
        require(query.isNotBlank()) { "请输入搜索内容" }
        val hits = mutableListOf<SearchHit>()
        root.walkTopDown()
            .onEnter { directory -> directory.name != ".git" && hits.size < maxResults }
            .filter { it.isFile && it.length() <= MAX_TEXT_BYTES }
            .forEach { file ->
                if (hits.size >= maxResults || !isProbablyText(file)) return@forEach
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (hits.size < maxResults && line.contains(query, ignoreCase = true)) {
                            hits += SearchHit(relative(root, file), index + 1, line.trim().take(180))
                        }
                    }
                }
            }
        hits
    }

    /** Extracts a ZIP below [targetRelativePath], rejecting zip-slip and resource exhaustion. */
    fun unzip(root: File, archiveRelativePath: String, targetRelativePath: String): Result<Int> = runCatching {
        val archive = resolve(root, archiveRelativePath).getOrThrow()
        require(archive.isFile) { "压缩包不存在" }
        val destination = resolve(root, targetRelativePath).getOrThrow()
        require(destination.mkdirs() || destination.isDirectory) { "无法创建解压目录" }
        val canonicalDestination = destination.canonicalFile
        var entries = 0
        var expanded = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val item = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ARCHIVE_ENTRIES) { "压缩包文件过多，已停止" }
                val output = File(canonicalDestination, item.name).canonicalFile
                require(output.path == canonicalDestination.path || output.path.startsWith(canonicalDestination.path + File.separator)) {
                    "压缩包包含越界路径: ${item.name}"
                }
                if (item.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) { "无法创建目录: ${item.name}" }
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { stream ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            require(expanded <= MAX_UNZIPPED_BYTES) { "解压后的内容超过 256 MB，已停止" }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        entries
    }

    /** Exports a text or Markdown file as a portable standalone HTML document in the workspace. */
    fun exportHtml(root: File, sourceRelativePath: String): Result<File> = runCatching {
        val source = resolve(root, sourceRelativePath).getOrThrow()
        require(source.isFile) { "请选择一个文件" }
        require(source.length() <= MAX_TEXT_BYTES) { "文件超过 1 MB，不能导出 HTML" }
        val body = source.readLines().joinToString("\n") { line ->
            val escaped = escapeHtml(line)
            when {
                line.startsWith("### ") -> "<h3>${escapeHtml(line.removePrefix("### "))}</h3>"
                line.startsWith("## ") -> "<h2>${escapeHtml(line.removePrefix("## "))}</h2>"
                line.startsWith("# ") -> "<h1>${escapeHtml(line.removePrefix("# "))}</h1>"
                line.isBlank() -> ""
                else -> "<p>$escaped</p>"
            }
        }
        val base = source.name.substringBeforeLast('.', source.name).ifBlank { "document" }
        val target = uniqueChild(source.parentFile ?: root, "$base.html")
        target.writeText(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escapeHtml(base)}</title><style>body{max-width:760px;margin:32px auto;padding:0 20px;font:16px/1.65 sans-serif;color:#17201d}pre,code{font-family:monospace}h1,h2,h3{line-height:1.25}</style><body>$body</body></html>"
        )
        target
    }

    fun uniqueChild(parent: File, preferredName: String): File {
        val dot = preferredName.lastIndexOf('.')
        val base = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val ext = if (dot > 0) preferredName.substring(dot) else ""
        var index = 0
        while (true) {
            val candidate = File(parent, if (index == 0) preferredName else "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun entry(root: File, file: File) = Entry(
        file = file,
        relativePath = relative(root, file),
        isDirectory = file.isDirectory,
        size = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified()
    )

    private fun relative(root: File, file: File): String = root.canonicalFile.toPath()
        .relativize(file.canonicalFile.toPath())
        .toString()
        .replace(File.separatorChar, '/')
        .trimEnd('/')

    private fun isProbablyText(file: File): Boolean = runCatching {
        val head = ByteArray(minOf(4_096, file.length().toInt()))
        file.inputStream().use { input -> input.read(head) }
        head.none { it == 0.toByte() }
    }.getOrDefault(false)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun globRegex(pattern: String): Pattern {
        val normalized = pattern.trim().replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "请输入文件匹配模式" }
        val out = StringBuilder("^")
        var index = 0
        while (index < normalized.length) {
            when (val ch = normalized[index]) {
                '*' -> if (index + 1 < normalized.length && normalized[index + 1] == '*') {
                    // `**/` also matches files directly below the root. A plain `.*`
                    // would incorrectly require at least one nested directory.
                    if (index + 2 < normalized.length && normalized[index + 2] == '/') {
                        out.append("(?:.*/)?")
                        index += 2
                    } else {
                        out.append(".*")
                        index++
                    }
                } else out.append("[^/]*")
                '?' -> out.append("[^/]")
                else -> out.append(Pattern.quote(ch.toString()))
            }
            index++
        }
        return Pattern.compile("$out$")
    }
}
