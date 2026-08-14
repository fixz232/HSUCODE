package com.hsucode.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行期工作区上下文。
 *
 * B2 修复:之前是纯进程级全局单例,多个会话并发时(后台会话仍在跑),文件/记忆工具会读到
 * 【最后切换到的那个会话】的工作区/项目,造成跨项目写错目录 + 记忆泄漏。
 *
 * 现在读取优先用【线程覆盖值】(由 [WorkspaceThreadElement] 在各会话自己的协程作用域内设置),
 * 没有覆盖时回退到【全局值】(设置页/无 per-session 上下文的后台核如 cron/goal 用)。
 * 这样每个会话核在自己的作用域里执行工具时,始终解析到【它自己】的工作区/项目,与前台切换无关。
 */
object WorkspaceContext {
    private const val TAG = "HsucodeOutput"
    private const val OUTPUT_FOLDER = "HSUCODE"

    /** 旧版共享存储根，仅用于识别旧配置；新安装默认使用应用可写目录。 */
    const val LEGACY_SHARED_ROOT = "/storage/emulated/0/HSUCODE"
    const val DEFAULT_ROOT = LEGACY_SHARED_ROOT

    @Volatile
    var defaultRoot: String = LEGACY_SHARED_ROOT
        private set

    // 全局兜底(可被设置/项目覆盖)。
    @Volatile
    private var globalRoot: String = defaultRoot
    @Volatile
    private var globalProjectId: Long = 0L

    /** Application context used to publish generated chat files to public Downloads. */
    @Volatile
    private var outputContext: Context? = null

    // 线程级覆盖(由协程 ThreadContextElement 设置);null = 用全局兜底。
    private val tlRoot = ThreadLocal<String?>()
    private val tlProjectId = ThreadLocal<Long?>()

    /** Public download location and its shareable MediaStore URI when the platform provides one. */
    data class PublishedFile(val location: String, val uri: Uri? = null)

    /** 当前生效的工作区根:优先线程覆盖,否则全局兜底。setter 写全局兜底(保持旧行为)。 */
    var workspaceRoot: String
        get() = tlRoot.get() ?: globalRoot
        set(value) { globalRoot = if (value.isBlank()) defaultRoot else value.trimEnd('/') }

    /** 在创建会话和工具之前配置一个确定可写的应用私有默认根。 */
    fun configureDefaultRoot(path: String) {
        val normalized = path.trim().trimEnd('/').ifBlank { LEGACY_SHARED_ROOT }
        val previous = defaultRoot
        defaultRoot = normalized
        if (globalRoot == previous || globalRoot == LEGACY_SHARED_ROOT) globalRoot = normalized
    }

    /** Configure the Android publisher once the Application is ready. */
    fun configureChatOutput(context: Context) {
        outputContext = context.applicationContext
    }

    /**
     * Copy a generated file into public Download/HSUCODE/yyyy-MM-dd.
     *
     * MediaStore is required on Android 10+ because direct File access to shared
     * Downloads is blocked by scoped storage. The workspace copy remains the source
     * of truth, so a publishing failure never loses the generated file.
     *
     * @return a user-visible relative location, or null when publishing is unavailable.
     */
    fun publishChatFile(source: File, displayName: String = source.name, mimeType: String? = null): String? =
        publishChatFileResult(source, displayName, mimeType)?.location

    /** Same publishing path as [publishChatFile], with a URI for open/share actions on Android 10+. */
    fun publishChatFileResult(source: File, displayName: String = source.name, mimeType: String? = null): PublishedFile? {
        val context = outputContext ?: return null
        if (!source.isFile) return null
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "file_${System.currentTimeMillis()}" }
        val relativeFolder = "${Environment.DIRECTORY_DOWNLOADS}/$OUTPUT_FOLDER/$dateFolder"
        val type = mimeType?.takeIf { it.isNotBlank() }
            ?: guessMimeType(safeName)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values)
                    ?: return null
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法打开下载目录输出流")
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                    PublishedFile("$relativeFolder/$safeName", uri)
                } catch (error: Throwable) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw error
                }
            } else {
                // API 28 and below do not have scoped storage. The manifest carries
                // WRITE_EXTERNAL_STORAGE for these releases; if permission is absent,
                // return null and keep the workspace copy intact.
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$OUTPUT_FOLDER/$dateFolder"
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val target = uniqueOutputFile(File(dir, safeName))
                source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                PublishedFile(target.absolutePath)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "publish chat file failed: ${error.message}")
            null
        }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "txt", "md", "log", "csv" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun uniqueOutputFile(initial: File): File {
        if (!initial.exists()) return initial
        val stem = initial.nameWithoutExtension
        val ext = initial.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 2
        var candidate: File
        do {
            candidate = File(initial.parentFile, "$stem ($index)$ext")
            index++
        } while (candidate.exists())
        return candidate
    }

    /** 当前生效的项目 id(记忆按项目隔离):优先线程覆盖,否则全局兜底。 */
    var projectId: Long
        get() = tlProjectId.get() ?: globalProjectId
        set(value) { globalProjectId = value }

    // —— 供 WorkspaceThreadElement 使用的线程覆盖读写 ——
    internal fun pushThread(root: String?, pid: Long?) { tlRoot.set(root); tlProjectId.set(pid) }
    internal fun peekThread(): Pair<String?, Long?> = tlRoot.get() to tlProjectId.get()
}
