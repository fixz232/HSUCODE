package com.hsucode.app

import com.hsucode.core.Tool
import com.hsucode.core.ToolResult
import com.hsucode.tools.SelfProtect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun shizukuSchema(vararg names: Pair<String, String>, required: List<String> = emptyList()): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            names.forEach { (name, description) -> put(name, JSONObject().put("type", "string").put("description", description)) }
        })
        put("required", JSONArray(required))
    }

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun shizukuResult(result: ShizukuCommandResult): ToolResult = when {
    result.timedOut -> ToolResult.Error("Shizuku 命令超时", 124, result.stderr.ifBlank { result.stdout })
    result.exitCode == 0 -> ToolResult.Success(result.stdout.ifBlank { "(no output)" })
    else -> ToolResult.Error("Shizuku 退出码 ${result.exitCode}", result.exitCode, result.stderr.ifBlank { result.stdout })
}

private suspend fun runShizuku(command: String): ToolResult = withContext(Dispatchers.IO) {
    SelfProtect.refuseCommand(command)?.let { return@withContext ToolResult.Error(it) }
    runCatching { ShizukuManager.execute(command) }
        .fold({ shizukuResult(it) }, { ToolResult.Error("Shizuku 执行失败：${it.message ?: "未知错误"}") })
}

internal abstract class EnabledShizukuTool(private val enabled: () -> Boolean) : Tool {
    protected fun unavailable(): ToolResult.Error? = when {
        !enabled() -> ToolResult.Error("请先在设置中开启 Shizuku 增强通道")
        !ShizukuManager.refresh().usable -> ToolResult.Error(ShizukuManager.state.value.lastError ?: "Shizuku 尚未授权")
        else -> null
    }

    override fun isAvailable(): Boolean = enabled()
    override fun unavailableReason(): String = "请先在设置中开启并授权 Shizuku 增强通道"
}

/** File operations through the Shizuku shell. Paths can be Android shared-storage or system-shell paths. */
internal class ShizukuFileTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_file"
    override val description = "Manage files using the user-authorized Shizuku shell. Actions: list, read, write, mkdir, delete, move, copy, find, info, zip, unzip. Uses explicit paths and remains protected by HSUCODE permission approval."
    override val parametersSchema = shizukuSchema(
        "action" to "list/read/write/mkdir/delete/move/copy/find/info/zip/unzip",
        "path" to "Primary source path",
        "target" to "Destination path for move/copy/zip/unzip",
        "content" to "Text content for write",
        "pattern" to "Filename pattern for find, for example *.log",
        "max_depth" to "Maximum find depth, default 4",
        required = listOf("action", "path")
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val action = params["action"]?.trim()?.lowercase().orEmpty()
        val path = params["path"]?.trim().orEmpty()
        val target = params["target"]?.trim().orEmpty()
        if (path.isBlank()) return ToolResult.Error("缺少 path 参数")
        listOf(path, target).filter { it.isNotBlank() }.firstNotNullOfOrNull(SelfProtect::refuse)?.let { return ToolResult.Error(it) }
        val command = when (action) {
            "list" -> "ls -la -- ${shellQuote(path)}"
            "read" -> "cat -- ${shellQuote(path)}"
            "write" -> {
                if (params["content"] == null) return ToolResult.Error("write 需要 content 参数")
                "mkdir -p -- ${shellQuote(path.substringBeforeLast('/', ".").ifBlank { "." })} && printf %s ${shellQuote(params["content"].orEmpty())} > ${shellQuote(path)}"
            }
            "mkdir" -> "mkdir -p -- ${shellQuote(path)}"
            "delete" -> "rm -rf -- ${shellQuote(path)}"
            "move" -> if (target.isBlank()) return ToolResult.Error("move 需要 target 参数") else "mv -- ${shellQuote(path)} ${shellQuote(target)}"
            "copy" -> if (target.isBlank()) return ToolResult.Error("copy 需要 target 参数") else "cp -R -- ${shellQuote(path)} ${shellQuote(target)}"
            "find" -> {
                val depth = params["max_depth"]?.toIntOrNull()?.coerceIn(1, 32) ?: 4
                val pattern = params["pattern"]?.takeIf { it.isNotBlank() } ?: "*"
                "find ${shellQuote(path)} -maxdepth $depth -name ${shellQuote(pattern)} -print"
            }
            "info" -> "stat -- ${shellQuote(path)}"
            "zip" -> if (target.isBlank()) return ToolResult.Error("zip 需要 target 参数") else
                "mkdir -p -- ${shellQuote(target.substringBeforeLast('/', ".").ifBlank { "." })} && (command -v zip >/dev/null || { echo '系统未提供 zip 命令' >&2; exit 127; }) && zip -r -- ${shellQuote(target)} ${shellQuote(path)}"
            "unzip" -> if (target.isBlank()) return ToolResult.Error("unzip 需要 target 参数") else
                "mkdir -p -- ${shellQuote(target)} && (command -v unzip >/dev/null || { echo '系统未提供 unzip 命令' >&2; exit 127; }) && unzip -o -- ${shellQuote(path)} -d ${shellQuote(target)}"
            else -> return ToolResult.Error("不支持的 action：$action")
        }
        return runShizuku(command)
    }
}

/** Android app and system operations mapped to typed inputs instead of model-written shell fragments. */
internal class ShizukuSystemTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_system"
    override val description = "Use Shizuku debugger capabilities for Android system tasks. Actions: list_packages, app_path, launch_app, force_stop, install_apk, uninstall_app, get_setting, put_setting, get_property, set_property, list_processes, kill_process, battery, activities."
    override val parametersSchema = shizukuSchema(
        "action" to "System action",
        "package" to "Android package name",
        "path" to "APK path for install",
        "namespace" to "settings namespace: system, secure, or global",
        "key" to "Setting or property key",
        "value" to "Value for put_setting or set_property",
        "pid" to "Process id for kill_process",
        "user_id" to "Android user id for uninstall, default 0",
        required = listOf("action")
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val action = params["action"]?.trim()?.lowercase().orEmpty()
        val pkg = params["package"]?.trim().orEmpty()
        val key = params["key"]?.trim().orEmpty()
        fun validPackage(value: String): Boolean = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(value)
        fun validKey(value: String): Boolean = Regex("[A-Za-z0-9_.:-]+") .matches(value)
        val command = when (action) {
            "list_packages" -> "pm list packages"
            "app_path" -> if (!validPackage(pkg)) return ToolResult.Error("需要合法 package 名") else "pm path ${shellQuote(pkg)}"
            "launch_app" -> if (!validPackage(pkg)) return ToolResult.Error("需要合法 package 名") else "monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1"
            "force_stop" -> if (!validPackage(pkg)) return ToolResult.Error("需要合法 package 名") else "am force-stop ${shellQuote(pkg)}"
            "install_apk" -> {
                val path = params["path"]?.trim().orEmpty()
                if (path.isBlank()) return ToolResult.Error("install_apk 需要 path 参数")
                SelfProtect.refuse(path)?.let { return ToolResult.Error(it) }
                "pm install -r ${shellQuote(path)}"
            }
            "uninstall_app" -> {
                if (!validPackage(pkg)) return ToolResult.Error("需要合法 package 名")
                val user = params["user_id"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                "pm uninstall --user $user ${shellQuote(pkg)}"
            }
            "get_setting" -> {
                val namespace = params["namespace"]?.trim().orEmpty()
                if (namespace !in setOf("system", "secure", "global") || !validKey(key)) return ToolResult.Error("需要合法 namespace 和 key")
                "settings get $namespace ${shellQuote(key)}"
            }
            "put_setting" -> {
                val namespace = params["namespace"]?.trim().orEmpty()
                if (namespace !in setOf("system", "secure", "global") || !validKey(key) || params["value"] == null) return ToolResult.Error("put_setting 需要 namespace、key、value")
                "settings put $namespace ${shellQuote(key)} ${shellQuote(params["value"].orEmpty())}"
            }
            "get_property" -> if (!validKey(key)) return ToolResult.Error("需要合法 property key") else "getprop ${shellQuote(key)}"
            "set_property" -> if (!validKey(key) || params["value"] == null) return ToolResult.Error("set_property 需要 key、value") else "setprop ${shellQuote(key)} ${shellQuote(params["value"].orEmpty())}"
            "list_processes" -> "ps -A"
            "kill_process" -> {
                val pid = params["pid"]?.toIntOrNull()?.takeIf { it > 1 } ?: return ToolResult.Error("需要大于 1 的 pid")
                "kill $pid"
            }
            "battery" -> "dumpsys battery"
            "activities" -> "dumpsys activity activities"
            else -> return ToolResult.Error("不支持的 action：$action")
        }
        return runShizuku(command)
    }
}

/** Screen and UIAutomator operations executed as the Shizuku shell identity. */
internal class ShizukuUiTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_ui"
    override val description = "Automate Android UI through the Shizuku shell. Actions: tap, long_press, swipe, key, text, home, back, recents, screenshot, dump. Every action is presented to the permission gate before execution."
    override val parametersSchema = shizukuSchema(
        "action" to "tap/long_press/swipe/key/text/home/back/recents/screenshot/dump",
        "x" to "X coordinate",
        "y" to "Y coordinate",
        "end_x" to "Swipe end X",
        "end_y" to "Swipe end Y",
        "duration_ms" to "Gesture duration in milliseconds",
        "keycode" to "Android keycode for key action",
        "text" to "Text for text action",
        "path" to "Output path for screenshot or dump",
        required = listOf("action")
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val action = params["action"]?.trim()?.lowercase().orEmpty()
        fun coordinate(name: String): Int? = params[name]?.toIntOrNull()?.takeIf { it in 0..20_000 }
        val command = when (action) {
            "tap", "long_press" -> {
                val x = coordinate("x") ?: return ToolResult.Error("需要有效 x 坐标")
                val y = coordinate("y") ?: return ToolResult.Error("需要有效 y 坐标")
                val duration = if (action == "long_press") params["duration_ms"]?.toIntOrNull()?.coerceIn(300, 10_000) ?: 800 else 1
                "input swipe $x $y $x $y $duration"
            }
            "swipe" -> {
                val x = coordinate("x") ?: return ToolResult.Error("需要有效 x 坐标")
                val y = coordinate("y") ?: return ToolResult.Error("需要有效 y 坐标")
                val endX = coordinate("end_x") ?: return ToolResult.Error("需要有效 end_x 坐标")
                val endY = coordinate("end_y") ?: return ToolResult.Error("需要有效 end_y 坐标")
                val duration = params["duration_ms"]?.toIntOrNull()?.coerceIn(1, 10_000) ?: 300
                "input swipe $x $y $endX $endY $duration"
            }
            "key" -> {
                val keycode = params["keycode"]?.toIntOrNull()?.takeIf { it in 1..300 } ?: return ToolResult.Error("需要 1 到 300 的 keycode")
                "input keyevent $keycode"
            }
            "text" -> {
                val text = params["text"] ?: return ToolResult.Error("text 操作需要 text 参数")
                "input text ${shellQuote(text.replace("%", "%25").replace(" ", "%s"))}"
            }
            "home" -> "input keyevent KEYCODE_HOME"
            "back" -> "input keyevent KEYCODE_BACK"
            "recents" -> "input keyevent KEYCODE_APP_SWITCH"
            "screenshot" -> {
                val path = params["path"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "/sdcard/Pictures/HSUCODE/shizuku-${System.currentTimeMillis()}.png"
                SelfProtect.refuse(path)?.let { return ToolResult.Error(it) }
                "mkdir -p -- ${shellQuote(path.substringBeforeLast('/', "."))} && screencap -p ${shellQuote(path)} && printf '保存到 %s\\n' ${shellQuote(path)}"
            }
            "dump" -> {
                val path = params["path"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "/sdcard/Download/HSUCODE/window-${System.currentTimeMillis()}.xml"
                SelfProtect.refuse(path)?.let { return ToolResult.Error(it) }
                "mkdir -p -- ${shellQuote(path.substringBeforeLast('/', "."))} && uiautomator dump ${shellQuote(path)} >/dev/null && cat -- ${shellQuote(path)}"
            }
            else -> return ToolResult.Error("不支持的 action：$action")
        }
        return runShizuku(command)
    }
}

internal class ShizukuProcessStartTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_process_start"
    override val description = "Start a long-running Shizuku shell process and return its process registry id. Use shizuku_process_status to inspect output and shizuku_process_stop to terminate it."
    override val parametersSchema = shizukuSchema("command" to "Shell command", "label" to "Optional process label", required = listOf("command"))

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val command = params["command"]?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.Error("缺少 command 参数")
        SelfProtect.refuseCommand(command)?.let { return ToolResult.Error(it) }
        return runCatching { ShizukuManager.startBackgroundProcess(command, params["label"].orEmpty().ifBlank { command.take(48) }) }
            .fold({ ToolResult.Success("已启动后台进程：$it") }, { ToolResult.Error("启动 Shizuku 后台进程失败：${it.message}") })
    }
}

internal class ShizukuProcessStatusTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_process_status"
    override val description = "Inspect registered Shizuku background processes and their recent stdout/stderr."
    override val parametersSchema = shizukuSchema("id" to "Optional process registry id")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val id = params["id"]?.trim().orEmpty()
        val snapshots = if (id.isBlank()) ShizukuManager.processes.value else listOfNotNull(ShizukuManager.processSnapshot(id))
        if (snapshots.isEmpty()) return ToolResult.Success("没有匹配的 Shizuku 后台进程")
        return ToolResult.Success(snapshots.joinToString("\n\n") { process ->
            buildString {
                append("id=${process.id} running=${process.running} exit=${process.exitCode ?: "-"}\n")
                append("command=${process.command}\n")
                if (process.stdout.isNotBlank()) append("stdout:\n${process.stdout.takeLast(4_000)}\n")
                if (process.stderr.isNotBlank()) append("stderr:\n${process.stderr.takeLast(4_000)}")
            }.trimEnd()
        })
    }
}

internal class ShizukuProcessStopTool(enabled: () -> Boolean) : EnabledShizukuTool(enabled) {
    override val name = "shizuku_process_stop"
    override val description = "Stop a registered Shizuku background process by id."
    override val parametersSchema = shizukuSchema("id" to "Process registry id", required = listOf("id"))

    override suspend fun execute(params: Map<String, String>): ToolResult {
        unavailable()?.let { return it }
        val id = params["id"]?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.Error("缺少 id 参数")
        return if (ShizukuManager.stopBackgroundProcess(id)) ToolResult.Success("已停止后台进程：$id")
        else ToolResult.Error("找不到后台进程：$id")
    }
}
