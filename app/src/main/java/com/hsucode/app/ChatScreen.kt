package com.hsucode.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.hsucode.app.R
import com.hsucode.tools.WorkspaceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/** Pending attachment waiting to be sent with the next message. */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val absolutePath: String = "",
    val sizeBytes: Long = 0,
    val mimeType: String = "",
    val content: String
)

/**
 * 处理选择器返回的一个 URI。**必须在 IO 线程调用**(内部有同步文件读写)。
 *
 * 大小【不设上限】(用户要求)。真正做到无上限的办法不是"敢读多大读多大",而是让大文件
 * 根本不进消息体:
 *  - 图片:流式复制到私有目录,只记路径,由 describe_image 按需读;
 *  - 文本:小文件照旧内联进消息(方便直接看);超过 [INLINE_TEXT_LIMIT] 的同样只落盘给路径,
 *    让模型用 file_read 按需读、分段读。否则几 MB 的日志会把上下文顶爆,
 *    换来一个来自服务端的 context_length_exceeded —— 报错指向不明,用户根本猜不到是附件太大。
 *
 * 这里的阈值只决定"内联还是给路径",不拦截任何文件。
 */
private const val INLINE_TEXT_LIMIT = 256 * 1024

private suspend fun processAttachmentUri(
    context: android.content.Context,
    uri: Uri,
    pending: MutableState<List<Attachment>>,
    workspaceRoot: String
) {
    // Toast 必须回主线程弹,否则在 IO 线程上没有 Looper 会直接抛异常。
    suspend fun toast(msg: String, long: Boolean = false) = withContext(Dispatchers.Main) {
        Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    suspend fun addAttachment(a: Attachment) = withContext(Dispatchers.Main) {
        pending.value = pending.value + a
    }

    val resolver = context.contentResolver
    var fileName = "unknown"
    var size = 0L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "unknown"
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }

    val mime = resolver.getType(uri).orEmpty()
    val ext = fileName.substringAfterLast('.', "").lowercase()

    /** 流式复制到应用私有目录,返回落盘后的文件;失败返回 null。全程不占内存。 */
    fun copyToWorkspace(): Pair<java.io.File, String>? {
        return try {
            val root = WorkspaceFileOps.root(workspaceRoot.ifBlank { WorkspaceContext.DEFAULT_ROOT }).getOrThrow()
            val dir = java.io.File(root, ".hsucode-attachments").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = java.io.File(dir, "${System.currentTimeMillis()}_$safeName")
            val stream = resolver.openInputStream(uri) ?: return null
            stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            dest to ".hsucode-attachments/${dest.name}"
        } catch (_: Exception) { null }
    }

    // ---- 图片:落盘存路径,不读内容 ----
    // 以前图片和文本文件走同一条路,两道坎都过不去:图片扩展名不在白名单里会被拒,
    // 就算放行,reader().readText() 把二进制按 UTF-8 读出来也只是一堆乱码。
    if (mime.startsWith("image/") || ext in imageExts) {
        val (dest, relativePath) = copyToWorkspace() ?: run { toast("无法读取图片: $fileName"); return }
        addAttachment(Attachment(
            fileName = fileName,
            absolutePath = relativePath,
            sizeBytes = if (size > 0) size else dest.length(),
            mimeType = mime.ifBlank { "image/$ext" },
            content = ""      // 图片内容不入消息,只留路径
        ))
        return
    }

    // Office/PDF 是二进制附件：保留应用私有副本并交给 document_extract / document_convert，
    // 绝不能把它们当 UTF-8 文本直接读入消息，否则会出现乱码、超大上下文和无响应。
    if (ext in setOf("pdf", "docx", "pptx", "xlsx", "xls", "odt", "odp")) {
        val (dest, relativePath) = copyToWorkspace() ?: run { toast("无法读取文档: $fileName"); return }
        addAttachment(Attachment(
            fileName = fileName,
            absolutePath = relativePath,
            sizeBytes = if (size > 0) size else dest.length(),
            mimeType = mime.ifBlank { "application/octet-stream" },
            content = ""
        ))
        toast("文档已附带，AI 可用 document_extract 读取")
        return
    }

    // ---- 文本文件:白名单 + 按大小决定内联还是给路径 ----
    val nameNoExt = fileName.substringBeforeLast('.')
    val allowed = whiteList.contains(ext) || whiteListNoExt.contains(nameNoExt) ||
        whiteListNoExt.contains(fileName)
    if (!allowed) {
        toast("暂不支持该文件类型: $fileName")
        return
    }

    // 大文本走路径,不读进内存 —— 既不会 OOM,也不会顶爆上下文。
    if (size > INLINE_TEXT_LIMIT) {
        val (dest, relativePath) = copyToWorkspace() ?: run { toast("读取失败: $fileName"); return }
        addAttachment(Attachment(
            fileName = fileName,
            absolutePath = relativePath,
            sizeBytes = if (size > 0) size else dest.length(),
            mimeType = mime,
            content = ""
        ))
        toast("文件较大,已按路径附带,AI 会按需读取")
        return
    }

    try {
        val content = resolver.openInputStream(uri)?.use { it.reader().readText() } ?: ""
        addAttachment(Attachment(
            fileName = fileName,
            sizeBytes = size,
            mimeType = mime,
            content = content
        ))
    } catch (e: OutOfMemoryError) {
        // size 取不到(部分 provider 不给 SIZE 列)时可能漏过上面的阈值判断,这里兜底。
        toast("文件太大,内存装不下:$fileName", long = true)
    } catch (e: Exception) {
        toast("读取失败: ${e.message}")
    }
}

private val whiteList = setOf(
    "txt","md","markdown","log","json","yaml","yml","toml","ini","properties","conf","cfg",
    "xml","html","htm","csv","tsv","kt","java","py","sh","bash","zsh","rs","go","c","cpp",
    "cc","h","hpp","js","ts","tsx","jsx","css","scss","less","vue","svelte","gradle",
    "gradle.kts","pro","cmake","dockerfile","gitignore","gitattributes","editorconfig",
    "sql","graphql","gql","env","env.example"
)
private val whiteListNoExt = setOf("README","LICENSE","Makefile","Dockerfile","CMakeLists.txt")

/** 图片扩展名。MIME 缺失时(部分文件管理器不给 type)靠它兜底判断。 */
private val imageExts = setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif","avif")

/** 人类可读体积,用于附件 chip。 */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1fG", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format("%.1fM", bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024}K"
    else -> "${bytes}B"
}

// Palette now sourced from [LocalHsuColors] — supports light/dark switching.
// Kept as local vals inside each composable so existing code paths compile unchanged.

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

// -- Unified icon button (terminal aesthetic, all monochrome) --

@Composable
private fun HsuIcon(
    icon: ImageVector,
    size: Dp = 18.dp,
    tint: Color = LocalHsuColors.current.ink,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null
) {
    if (onClick != null) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription, Modifier.size(size), tint = tint)
        }
    } else {
        Icon(icon, contentDescription, Modifier.size(size), tint = tint)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatScreen(
    chatState: ChatStateLike,
    conversationTitle: String = "新聊天",
    assistantName: String = "默认助手",
    currentModel: String = "",
    supplierId: String = "",
    providerName: String = "",
    workspaceRoot: String = "",
    availableModels: List<String> = emptyList(),
    onSwitchModel: (String) -> Unit = {},
    thinkingEnabled: Boolean = false,
    thinkingLevel: Int = 2,
    onThinkingEnabledChange: (Boolean) -> Unit = {},
    onThinkingLevelChange: (Int) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkflow: () -> Unit = {},
    onNavigateToGoal: () -> Unit = {},
    onNavigateToAgentScene: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    subAgentActive: Boolean = false,
    ttsHelper: TtsHelper? = null,
    voiceInputHelper: VoiceInputHelper? = null,
    powerMode: com.hsucode.core.PowerMode = com.hsucode.core.PowerMode.NORMAL,
    onOpenDrawer: () -> Unit = {},
    onNewChat: () -> Unit = {},
    planState: PlanState? = null,
    tokenStats: TokenStats = TokenStats.EMPTY,
    onRegenerate: (Long) -> Unit = {},
    onDeleteMessage: (Long) -> Unit = {},
    onCompactContext: () -> Unit = {},
    skillNames: List<String> = emptyList(),
    onInsertSkill: (String) -> Unit = {},
    mcpNames: List<String> = emptyList(),
    onInsertMcp: (String) -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onSetConversationWorkspace: (String) -> Unit = {},
    onSetWebSearchEnabled: (Boolean) -> Unit = {},
    // 计划模式:输入框内切换。PLAN=只读+规划、不执行写/命令;ASK=正常聊天。
    permissionMode: com.hsucode.security.PermissionMode = com.hsucode.security.PermissionMode.ASK,
    onUpdatePermissionMode: (com.hsucode.security.PermissionMode) -> Unit = {},
    // 协作模式(主脑+子智能体):与计划模式一起在输入框模式卡片里选。
    collabMode: Boolean = false,
    onSetCollabMode: (Boolean) -> Unit = {},
    // ---- Goal/Work 模式 ----
    isGoalSession: Boolean = false,
    goalStatusCode: String = "",          // ""/running/achieved/failed(来自 DB)
    goalLiveText: String = "",            // 实时状态小字(第 N 轮/裁判评估中…)
    goalRunning: Boolean = false,
    onStartGoal: (String) -> Unit = {},
    onStopGoal: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var unreadCount by remember(chatState) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val voiceState = voiceInputHelper?.state?.collectAsState()?.value ?: VoiceInputHelper.State.IDLE
    val voiceFinalText = voiceInputHelper?.finalText?.collectAsState()?.value.orEmpty()
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voiceInputHelper?.startListening()
        else Toast.makeText(context, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(voiceFinalText) {
        if (voiceFinalText.isNotBlank()) {
            chatState.input.value = listOf(chatState.input.value.trim(), voiceFinalText.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
            voiceInputHelper?.reset()
        }
    }
    // 回车行为开关(App 层可观察设置):true=回车发送;false=回车换行。读它即响应式。
    val enterToSend = (context.applicationContext as HsucodeApplication).enterToSend
    var pendingModelIdx by remember { mutableStateOf<String?>(null) }
    val xc = LocalHsuColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border

    // Menu visibility
    var showMainMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }

    // TTS state
    val ttsEnabled by (ttsHelper?.enabled?.collectAsState() ?: remember { mutableStateOf(false) })
    val lastMsgCount = remember { mutableStateOf(0) }

    // Live token stats + 上下文占用 —— 消息变化时刷新;流式期间每秒轮询一次(修「刷新不及时」)。
    var liveTokenStats by remember { mutableStateOf(tokenStats) }
    var contextUsage by remember { mutableStateOf(ContextUsage.EMPTY) }
    LaunchedEffect(chatState.messages.size, chatState.isStreaming.value) {
        val agentChat = chatState as? AgentChatState ?: return@LaunchedEffect
        do {
            liveTokenStats = agentChat.getSessionTokenStats()
            contextUsage = agentChat.getContextUsage()
            if (chatState.isStreaming.value) kotlinx.coroutines.delay(500)
        } while (chatState.isStreaming.value)
    }

    // ---- attachments ----
    val pendingAttachments = remember { mutableStateOf<List<Attachment>>(emptyList()) }
    // 附件读取一律走 IO 线程。ActivityResult 回调跑在主线程,而复制/读取都是同步 IO——
    // 取消大小上限之后,选一个大文件会直接把 UI 卡死触发 ANR。原来有 200KB 限制时
    // 这个问题被掩盖着,现在必须显式挪走。
    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { result: List<Uri>? ->
        if (result == null) return@rememberLauncherForActivityResult
        scope.launch {
            result.forEach { uri ->
                withContext(Dispatchers.IO) { processAttachmentUri(context, uri, pendingAttachments, workspaceRoot) }
            }
        }
    }
    // 相册取图。
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { processAttachmentUri(context, uri, pendingAttachments, workspaceRoot) }
        }
    }

    // 「+」卡片 + 文件夹/技能选择器 状态;联网搜索、深度分析 开关。
    var showPlusCard by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showMcpPicker by remember { mutableStateOf(false) }
    var showStatsPopup by remember { mutableStateOf(false) }   // 点圆环弹出的统计卡片
    var expandingPrompt by remember { mutableStateOf(false) }  // 正在扩展提示词
    var showModeCard by remember { mutableStateOf(false) }     // 计划/协作模式卡片
    var webSearchOn by remember { mutableStateOf(com.hsucode.tools.WebSearchGate.enabled) }

    LaunchedEffect(chatState.messages.size, ttsEnabled) {
        val newCount = chatState.messages.size
        if (newCount > lastMsgCount.value && ttsEnabled && !chatState.isStreaming.value) {
            val lastMsg = chatState.messages.lastOrNull()
            if (lastMsg != null && lastMsg.role == "assistant" && lastMsg.content.isNotBlank()) {
                ttsHelper?.speak(lastMsg.content)
            }
        }
        lastMsgCount.value = newCount
    }

    // Model display name (shorten long IDs smartly)
    val modelDisplayName = remember(currentModel) {
        when {
            currentModel.length <= 15 -> currentModel
            currentModel.startsWith("deepseek-") -> currentModel.removePrefix("deepseek-")
            else -> {
                val parts = currentModel.split("-")
                if (parts.size >= 3) parts.takeLast(2).joinToString("-")
                else currentModel.take(15)
            }
        }
    }
    val effortLabels = listOf("Low", "Medium", "High", "Extra", "Max")
    val effortLabel = effortLabels.getOrElse(thinkingLevel) { "High" }

    // Bind scope on (re)composition — keyed on the chatState INSTANCE so切换会话换绑新实例时会重新布线
    // (statusLine / isStreaming 收集器绑到新会话那一对)。旧实例仍在其自有 scope 后台运行,不受影响。
    LaunchedEffect(chatState) {
        // M1 修复:用 LaunchedEffect 自身的协程作用域(this)布线,而非 composition 级 scope ——
        // 这样切会话(key=chatState 变化)时,旧实例的状态收集器随本 effect 一并取消,不再泄漏被驱逐的 core。
        val legacy = chatState as? ChatState
        if (legacy != null) {
            legacy.init(this)
            legacy.loadHistory()            // 旧版 ChatState 仍自行加载
        }
        // AgentChatState 的历史由 app(冷启动 + switchToSession)负责加载,这里只布线,避免打断后台正在跑的会话。
        (chatState as? AgentChatState)?.init(this)
    }

    val turnGroups by remember(chatState.messages.toList()) {
        derivedStateOf { chatState.messages.toList().groupByTurn() }
    }

    // Follow the tail for both newly-added messages and streaming content updates.
    // Watching only messages.size misses token-by-token replacements of the last item,
    // which was why a long answer could grow below the viewport without moving the list.
    // Once the user scrolls away from the tail, auto-follow pauses and the unread counter
    // keeps the manual jump-to-bottom action available.
    LaunchedEffect(chatState) {
        var initialized = false
        var observedCount = 0
        var previousContentRevision: Triple<Long, Int, Int>? = null
        var lastScrollAtNanos = 0L

        snapshotFlow {
            val last = chatState.messages.lastOrNull()
            Triple(
                chatState.messages.size,
                Triple(last?.id ?: -1L, last?.content?.length ?: 0, last?.reasoning?.length ?: 0),
                chatState.isStreaming.value
            )
        }.collect { revision ->
            val currentCount = revision.first
            val contentRevision = revision.second

            if (!initialized) {
                initialized = true
                observedCount = currentCount
                previousContentRevision = contentRevision
                if (currentCount > 0) {
                    kotlinx.coroutines.yield()
                    val target = listState.layoutInfo.totalItemsCount - 1
                    if (target >= 0) listState.scrollToItem(target, scrollOffset = 1_000_000)
                    lastScrollAtNanos = System.nanoTime()
                }
                return@collect
            }

            val added = (currentCount - observedCount).coerceAtLeast(0)
            val contentChanged = contentRevision != previousContentRevision
            val atTail = nearBottom
            if (added > 0 && !atTail) unreadCount += added

            // New messages jump immediately. Streaming text is throttled to 80ms so the
            // list follows smoothly without starting an animation for every token.
            val now = System.nanoTime()
            val shouldScroll = atTail && (added > 0 || contentChanged) &&
                (added > 0 || now - lastScrollAtNanos >= 80_000_000L)
            if (shouldScroll) {
                kotlinx.coroutines.yield()
                if (nearBottom) {
                    val target = listState.layoutInfo.totalItemsCount - 1
                    if (target >= 0) listState.scrollToItem(target, scrollOffset = 1_000_000)
                    lastScrollAtNanos = now
                }
            }

            observedCount = currentCount
            previousContentRevision = contentRevision
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) unreadCount = 0
    }

    Column(
        Modifier.fillMaxSize().background(Bg).imePadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Menu, contentDescription = "打开会话列表", tint = Ink)
            }
            ProviderLogo(
                supplierId = supplierId,
                providerName = providerName.ifBlank { assistantName },
                logoSize = 32.dp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column(
                Modifier.weight(1f).padding(horizontal = 8.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        showMainMenu = true
                        showEffortMenu = false
                    }
            ) {
                Text(
                    conversationTitle.ifBlank { "新聊天" },
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$modelDisplayName · ${if (chatState.isStreaming.value) "正在生成" else "已就绪"}",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showHeaderMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多会话操作", tint = Sub)
                }
                DropdownMenu(expanded = showHeaderMenu, onDismissRequest = { showHeaderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("打开终端") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                        onClick = { showHeaderMenu = false; onNavigateToTerminal() }
                    )
                    DropdownMenuItem(
                        text = { Text("打开工作区") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        onClick = { showHeaderMenu = false; onNavigateToWorkspace() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (subAgentActive) "进入指挥室 · 运行中" else "进入指挥室") },
                        leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                        onClick = { showHeaderMenu = false; onNavigateToAgentScene() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享对话") },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        onClick = {
                            showHeaderMenu = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, chatState.formatForExport())
                                putExtra(Intent.EXTRA_SUBJECT, "HSUCODE 对话导出")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享对话"))
                        }
                    )
                }
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.AddComment, contentDescription = "新建聊天", tint = Sub)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))

        ExecutionStrip(
            isGoalSession = isGoalSession,
            goalStatusCode = goalStatusCode,
            goalLiveText = goalLiveText,
            goalRunning = goalRunning,
            planState = planState,
            statusLine = chatState.statusLine.value,
            isRunning = chatState.isStreaming.value,
            onStop = onStopGoal
        )
        if (chatState.isStreaming.value) {
            WorkspaceQuickStrip(
                linuxReady = WorkspaceRuntime.hasLinux(),
                onOpenTerminal = onNavigateToTerminal,
                onOpenWorkspace = onNavigateToWorkspace
            )
        }

        val agentState = chatState as? AgentChatState
        val confirmReq = agentState?.pendingConfirm?.value
        // Keep a pending request from becoming a stale card when the mode is changed
        // from Settings while this chat is waiting for approval. The mode change is
        // persisted at application scope, so resolve the suspended AgentCore here.
        LaunchedEffect(permissionMode, agentState) {
            if (permissionMode == com.hsucode.security.PermissionMode.ALLOW_ALL &&
                agentState?.pendingConfirm?.value != null
            ) {
                agentState.approveAlwaysConfirmation()
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (turnGroups.isEmpty()) {
                item(key = "welcome_panel") {
                    WelcomePanel(
                        currentModel = modelDisplayName,
                        providerName = providerName,
                        hasWorkspace = workspaceRoot.isNotBlank(),
                        onPrompt = { prompt -> chatState.input.value = prompt },
                        onOpenSettings = onNavigateToSettings,
                        onOpenWorkspace = onNavigateToWorkspace
                    )
                }
            }
            items(turnGroups, key = { it.key }) { group ->
                when {
                    group.isFlat && group.userMessage != null ->
                        MessageBubble(
                            group.userMessage,
                            isStreamingMessage = chatState.isStreaming.value && group.userMessage == chatState.messages.lastOrNull(),
                            onDelete = { onDeleteMessage(group.userMessage.id) },
                            // 点用户消息的「重答」= 用同样的问题重新问一遍
                            onRegenerate = { onRegenerate(group.userMessage.id) }
                        )
                    group.isFlat && group.assistantMessage != null ->
                        MessageBubble(
                            group.assistantMessage,
                            isStreamingMessage = chatState.isStreaming.value && group.assistantMessage == chatState.messages.lastOrNull(),
                            onRetry = if (group.assistantMessage.content.startsWith("✗ ")) {
                                { onRegenerate(group.assistantMessage.id) }
                            } else null,
                            onDelete = { onDeleteMessage(group.assistantMessage.id) },
                            onRegenerate = { onRegenerate(group.assistantMessage.id) }
                        )
                    group.isFlat && group.toolMessages.isNotEmpty() ->
                        group.toolMessages.forEach { toolMsg ->
                            toolMsg.contentBlock?.let { block ->
                                when (block) {
                                    is MessageContent.ToolCall -> ToolCallRow(block, modifier = Modifier)
                                    is MessageContent.FileRead -> CodeBlock(block, Modifier.padding(vertical = 4.dp))
                                    is MessageContent.FileEdit -> DiffBlock(block, Modifier.padding(vertical = 4.dp))
                                    else -> {}
                                }
                            }
                        }
                    else ->
                        AgentTurnBlock(
                            group,
                            isStreaming = chatState.isStreaming.value,
                            onRegenerate = group.assistantMessage?.let { a -> { onRegenerate(a.id) } },
                            onOpenWorkspace = onNavigateToWorkspace,
                        )
                }
            }
            // Confirmation card (rendered in chat flow)
            if (confirmReq != null) {
                item(key = "confirm_card") {
                    ConfirmCard(
                        command = confirmReq.command,
                        isIrreversible = confirmReq.isIrreversible,
                        onDeny = { agentState.denyConfirmation() },
                        onAllowOnce = { agentState.approveOnceConfirmation() },
                        onAlwaysAllow = {
                            // "总是允许" must survive an AgentCore/session rebuild.
                            // The former in-memory whitelist only covered one core and
                            // made the same approval card return after navigation.
                            onUpdatePermissionMode(com.hsucode.security.PermissionMode.ALLOW_ALL)
                            agentState.approveAlwaysConfirmation()
                        }
                    )
                }
            }
        }

        // ---- Scroll-to-bottom FAB — only visible when not at the tail ----
        val showFab = !nearBottom && chatState.messages.isNotEmpty()
        val fabScale by animateFloatAsState(
            targetValue = if (showFab) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            label = "fabScale"
        )
        if (fabScale > 0.02f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .height(48.dp)
                    .widthIn(min = 48.dp)
                    .graphicsLayer(scaleX = fabScale, scaleY = fabScale, alpha = fabScale)
                    .background(Ink, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch {
                            val target = listState.layoutInfo.totalItemsCount - 1
                            if (target >= 0) listState.animateScrollToItem(target)
                        }
                    }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "跳到底部", tint = Bg, modifier = Modifier.size(20.dp))
                    if (unreadCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(unreadCount.toString(), color = Bg, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        }

        // Token 统计不再单独占一条 —— 改为输入框发送键旁的「上下文圆环」,点击弹出统计卡片(见下)。

        // ---- slash command popup ----
        // 仅在还在敲【命令名本身】(斜杠后无空格)时显示菜单;一旦出现空格(如 /skill 后接任务)即隐藏。
        val activeSlashCommand = remember(chatState.input.value) {
            val v = chatState.input.value
            if (v.startsWith("/") && !v.drop(1).contains(' ')) v.substring(1).trim() else null
        }
        if (activeSlashCommand != null) {
            SlashCommandMenu(
                query = activeSlashCommand,
                skillNames = skillNames,
                onCompact = {
                    onCompactContext()
                    chatState.input.value = ""
                },
                // 选技能 → 输入框生成 /技能名 (由 onInsertSkill 处理),不再清空、不塞模板。
                onSelectSkill = { name -> onInsertSkill(name) },
                onClose = { chatState.input.value = "" }
            )
        }

        // Advanced capabilities stay out of the composer until they are needed.
        if (showPlusCard) {
            ModalBottomSheet(
                onDismissRequest = { showPlusCard = false },
                containerColor = Bg,
                contentColor = Ink,
            ) {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text("添加内容与能力", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text("附件优先，工具能力按需开启。", style = MaterialTheme.typography.bodySmall, color = Sub)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PlusAction(Icons.Outlined.Image, "图片", Ink, Sub) { showPlusCard = false; imageLauncher.launch("image/*") }
                        PlusAction(Icons.Outlined.Description, "文件", Ink, Sub) { showPlusCard = false; attachLauncher.launch(arrayOf("*/*")) }
                        PlusAction(Icons.Outlined.Folder, "文件夹", Ink, Sub) { showPlusCard = false; showFolderPicker = true }
                    }
                    HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 8.dp))
                    PlusRow(Icons.Outlined.AutoAwesome, "优化任务", "用 AI 补全当前描述", Ink, Sub, expandingPrompt) {
                        val draft = chatState.input.value
                        if (!expandingPrompt && draft.isNotBlank()) {
                            expandingPrompt = true
                            scope.launch {
                                val a = context.applicationContext as HsucodeApplication
                                val r = PromptExpander.expand(a.database, a.keystore, PromptExpander.Kind.TASK, draft)
                                r.onSuccess { chatState.input.value = it }
                                r.onFailure { Toast.makeText(context, "扩展失败:${it.message}", Toast.LENGTH_SHORT).show() }
                                expandingPrompt = false
                                showPlusCard = false
                            }
                        }
                    }
                    PlusRow(Icons.Outlined.MicNone, "语音输入", if (voiceState == VoiceInputHelper.State.LISTENING) "正在听取…" else "把语音转成消息", Ink, Sub, voiceState == VoiceInputHelper.State.LISTENING) {
                        if (voiceState == VoiceInputHelper.State.LISTENING) voiceInputHelper?.stopListening()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceInputHelper?.startListening()
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        showPlusCard = false
                    }
                    HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 8.dp))
                    PlusRow(Icons.Outlined.Bolt, "Skill", "选择要在本次对话使用的技能", Ink, Sub, null) { showPlusCard = false; showSkillPicker = true }
                    PlusRow(Icons.Outlined.Extension, "MCP", "选择外部工具服务器", Ink, Sub, null) { showPlusCard = false; showMcpPicker = true }
                    PlusRow(Icons.Outlined.FolderOpen, "工作区", "打开文件与 Linux 环境", Ink, Sub, null) {
                        showPlusCard = false; onNavigateToWorkspace()
                    }
                    PlusRow(Icons.Outlined.Public, "联网搜索", if (webSearchOn) "已开启" else "已关闭", Ink, Sub, webSearchOn) {
                        webSearchOn = !webSearchOn; onSetWebSearchEnabled(webSearchOn)
                    }
                    PlusRow(Icons.Outlined.Psychology, "深度分析", if (thinkingEnabled) "已开启" else "已关闭", Ink, Sub, thinkingEnabled) {
                        val next = !thinkingEnabled; onThinkingEnabledChange(next); if (next) onThinkingLevelChange(4)
                    }
                }
            }
        }
        if (showFolderPicker) {
            DirectoryPickerDialog(
                initialPath = workspaceRoot,
                onConfirm = { path -> onSetConversationWorkspace(path); showFolderPicker = false },
                onDismiss = { showFolderPicker = false }
            )
        }
        if (showSkillPicker) {
            SkillPickerDialog(skillNames = skillNames, onPick = { onInsertSkill(it); showSkillPicker = false }, onDismiss = { showSkillPicker = false })
        }
        if (showMcpPicker) {
            McpPickerDialog(
                mcpNames = mcpNames,
                onPick = { onInsertMcp(it); showMcpPicker = false },
                onManage = { showMcpPicker = false; onNavigateToMcp() },
                onDismiss = { showMcpPicker = false }
            )
        }

        // ---- floating input card ----
        // 统一「提交」动作:回车键(回车发送模式)与 [→] 键共用。运行中=中途插话(注入不打断);
        // Goal 未跑=以输入启动目标;否则正常发送(含附件拼接)。
        val submitInput: () -> Unit = submit@{
            if (chatState.isStreaming.value) {
                if (chatState.input.value.isNotBlank()) chatState.send()  // send() 内部走 steer 注入
                return@submit
            }
            if (isGoalSession && !goalRunning) {
                val g = chatState.input.value.trim()
                if (g.isNotEmpty()) { chatState.input.value = ""; onStartGoal(g) }
                return@submit
            }
            if (pendingAttachments.value.isNotEmpty()) {
                val attachmentText = buildString {
                    append(chatState.input.value.trim())
                    append("\n\n---\n附件:\n")
                    pendingAttachments.value.forEach { att ->
                        if (att.absolutePath.isNotEmpty()) {
                            // 走路径的附件:内容不进消息体,所以多大都不占上下文。
                            // 图片交给 describe_image(未配视觉模型时该工具不暴露),
                            // 大文本交给 file_read 按需读、分段读。
                            val isImg = att.mimeType.startsWith("image/")
                            if (isImg) {
                                append("\n### ${att.fileName}(图片,路径:${att.absolutePath})\n")
                            } else if (att.fileName.substringAfterLast('.', "").lowercase() in setOf("pdf", "docx", "pptx", "xlsx", "xls", "odt", "odp")) {
                                append("\n### ${att.fileName}(文档,路径:${att.absolutePath},请使用 document_extract 或 document_convert 处理)\n")
                            } else {
                                append("\n### ${att.fileName}(文件较大未内联,路径:${att.absolutePath},请用 file_read 按需读取)\n")
                            }
                        } else {
                            append("\n### ${att.fileName}\n```\n${att.content}\n```\n")
                        }
                    }
                    append("\n---\n")
                }
                chatState.input.value = attachmentText
                pendingAttachments.value = emptyList()
            }
            chatState.send()
        }

        Box(
            Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .background(xc.bgElevated, RoundedCornerShape(8.dp))
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                // Row 1: text field
                TextField(
                    value = chatState.input.value,
                    onValueChange = { chatState.input.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    // 始终可编辑:AI 运行中也能打字,以便「中途插话」不打断地注入指令。
                    enabled = true,
                    // 回车发送模式=单行(回车即发,换行用输入法组合键);回车换行模式=多行(靠 [→] 发)。
                    singleLine = enterToSend,
                    maxLines = if (enterToSend) 1 else 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Ink,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        disabledTextColor = Faint
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
                    keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContextRing(usage = contextUsage, onClick = { showStatsPopup = !showStatsPopup })
                            Spacer(Modifier.width(6.dp))
                            val streaming = chatState.isStreaming.value
                            val hasText = chatState.input.value.isNotBlank()
                            IconButton(
                                onClick = {
                                    if (streaming && !hasText) {
                                        if (isGoalSession && goalRunning) onStopGoal() else chatState.stop()
                                    } else submitInput()
                                },
                                enabled = hasText || streaming,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (streaming && !hasText) Icons.Outlined.Stop else Icons.Outlined.Send,
                                    contentDescription = if (streaming && !hasText) "停止生成" else "发送消息",
                                    tint = if (hasText || streaming) Ink else Faint
                                )
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            when {
                                chatState.isStreaming.value -> "插话给正在工作的 AI(不打断)…"
                                isGoalSession && !goalRunning -> "输入目标,让 HSUCODE 自主完成…"
                                else -> "输入消息…"
                            },
                            color = Sub, fontSize = 16.sp
                        )
                    }
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))

                // Row 1.5: attachment chips (only if any)
                if (pendingAttachments.value.isNotEmpty()) {
                    val chipScrollState = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScrollState)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingAttachments.value.forEach { att ->
                            Row(
                                Modifier
                                    .heightIn(min = 40.dp)
                                    .border(1.dp, Color(0x1A1A1A17), RoundedCornerShape(6.dp))
                                    .background(Color(0x0D1A1A17), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isImage = att.mimeType.startsWith("image/")
                                Icon(
                                    if (isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Ink
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    att.fileName,
                                    fontSize = 12.sp,
                                    fontFamily = JetBrainsMono,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 不再有大小上限,附件可能很大 —— 把体积标出来,
                                // 免得用户在毫不知情的情况下把几十 MB 的文本塞进上下文。
                                if (att.sizeBytes > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        humanSize(att.sizeBytes),
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMono,
                                        color = Sub
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        pendingAttachments.value = pendingAttachments.value.filter { it.id != att.id }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "删除附件", modifier = Modifier.size(16.dp), tint = Ink)
                                }
                            }
                        }
                    }
                }

                // Row 2: toolbar
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The composer keeps the recurring controls only. Provider and model
                    // identity remain in the header rather than repeating at the bottom.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HsuIcon(
                            icon = Icons.Outlined.Add,
                            size = 19.dp,
                            tint = if (showPlusCard) Green else Sub,
                            contentDescription = "附件与工具",
                            onClick = { showPlusCard = !showPlusCard }
                        )
                        val isFull = permissionMode == com.hsucode.security.PermissionMode.ALLOW_ALL
                        val autoApproveRisk = permissionMode == com.hsucode.security.PermissionMode.AUTO_APPROVE_RISK
                        val accessSuffix = when {
                            isFull -> " · 完全访问"
                            autoApproveRisk -> " · 自动批准"
                            else -> " · 请求批准"
                        }
                        val modeLabel = when {
                            collabMode -> "协作$accessSuffix"
                            permissionMode == com.hsucode.security.PermissionMode.PLAN -> "计划"
                            isFull -> "完全访问"
                            autoApproveRisk -> "帮我批准"
                            else -> "聊天"
                        }
                        Text(
                            modeLabel,
                            fontSize = 12.sp,
                            color = if (isFull) xc.red else if (collabMode || permissionMode == com.hsucode.security.PermissionMode.PLAN || autoApproveRisk) xc.green else Sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showModeCard = !showModeCard }
                                .padding(horizontal = 8.dp, vertical = 14.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { showMainMenu = !showMainMenu; showEffortMenu = false },
                            modifier = Modifier.height(48.dp)
                        ) { Text("模型", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }

        // ---- 模式卡片(计划 / 协作,各选 正常·完全访问)----
        if (showModeCard) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -70),
                onDismissRequest = { showModeCard = false }
            ) {
                ModeCard(
                    permissionMode = permissionMode,
                    collabMode = collabMode,
                    onPick = { mode, collab ->
                        onSetCollabMode(collab)
                        onUpdatePermissionMode(mode)
                        // The confirmation card's "总是允许" and the full-access
                        // selector use the same persisted mode. Resolve a currently
                        // waiting approval as well, so the current task does not keep
                        // showing a stale approval button.
                        if (mode == com.hsucode.security.PermissionMode.ALLOW_ALL) {
                            (chatState as? AgentChatState)?.approveAlwaysConfirmation()
                        }
                        showModeCard = false
                    }
                )
            }
        }

        // ---- 上下文统计弹卡(点圆环)----
        if (showStatsPopup) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -70),
                onDismissRequest = { showStatsPopup = false }
            ) {
                ContextStatsCard(
                    usage = contextUsage,
                    stats = liveTokenStats,
                    model = currentModel,
                    chatState = chatState,
                    onClose = { showStatsPopup = false }
                )
            }
        }

        // ---- Main Menu Popup ----
        if (showMainMenu) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -80),
                onDismissRequest = { showMainMenu = false; showEffortMenu = false }
            ) {
                Column(
                    Modifier
                        .background(Bg).border(0.5.dp, Border)
                        .padding(6.dp).widthIn(min = 200.dp, max = 280.dp)
                ) {
                    // (a) Current model — top, with ✓
                    Text(
                        "$currentModel  ✓",
                        fontSize = 12.sp,
                        fontFamily = JetBrainsMono,
                        color = Ink,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(Border))

                    // (b) Effort row with › arrow — click opens submenu
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showEffortMenu = true
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Effort: $effortLabel",
                            fontSize = 12.sp,
                            fontFamily = JetBrainsMono,
                            color = Ink
                        )
                        Text(
                            "›",
                            fontSize = 14.sp,
                            fontFamily = JetBrainsMono,
                            color = Faint
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(Border))

                    // (c) More models
                    if (availableModels.isNotEmpty()) {
                        Text(
                            "More models",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMono,
                            color = Faint,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        val scrollState = rememberScrollState()
                        Column(
                            Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(scrollState)
                        ) {
                            availableModels.forEach { name ->
                                val isActive = name == currentModel
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (isActive) LocalHsuColors.current.activeBg else Bg)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            showMainMenu = false
                                            showEffortMenu = false
                                            pendingModelIdx = name
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (isActive) "●" else "○",
                                        fontSize = 9.sp,
                                        color = if (isActive) Green else Faint,
                                        modifier = Modifier.width(14.dp)
                                    )
                                    Text(
                                        name,
                                        fontSize = 11.sp,
                                        fontFamily = JetBrainsMono,
                                        color = if (isActive) Ink else Sub
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Effort Submenu Popup (overlays main menu) ----
        if (showEffortMenu) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -80),
                onDismissRequest = { showEffortMenu = false }
            ) {
                Column(
                    Modifier
                        .background(Bg).border(0.5.dp, Border)
                        .padding(6.dp).widthIn(min = 200.dp, max = 280.dp)
                ) {
                    // (a) Hint
                    Text(
                        "强度越高回复越细致，但耗时更长",
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMono,
                        color = Faint,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(Border))

                    // (b) Five effort levels — all clickable
                    effortLabels.forEachIndexed { i, label ->
                        val sel = i == thinkingLevel
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    onThinkingLevelChange(i)
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (sel) "●" else "○",
                                fontSize = 10.sp,
                                color = if (sel) Green else Faint,
                                modifier = Modifier.width(16.dp)
                            )
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontFamily = JetBrainsMono,
                                color = if (sel) Ink else Sub
                            )
                            if (sel) Text("  ✓", fontSize = 10.sp, color = Green)
                            if (label == "Max") {
                                HsuIcon(
                                    icon = Icons.Outlined.Info,
                                    size = 12.dp,
                                    tint = Faint,
                                    onClick = {
                                        // tooltip: 耗用极大，谨慎使用
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(0.5.dp).background(Border))

                    // (c) Thinking toggle
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onThinkingEnabledChange(!thinkingEnabled)
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Thinking", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Ink)
                            Text("用于复杂任务的思考过程", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
                        }
                        Text(
                            if (thinkingEnabled) "ON ●──" else "OFF ──○",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMono,
                            color = if (thinkingEnabled) Green else Faint
                        )
                    }
                }
            }
        }
    }

    // ---- model switch warning ----
    if (pendingModelIdx != null) {
        val targetName = pendingModelIdx ?: ""
        AlertDialog(
            onDismissRequest = { pendingModelIdx = null },
            title = { Text("切换模型", fontFamily = JetBrainsMono, color = Ink) },
            text = { Text("切换到「$targetName」？\n\n新模型可能不兼容当前对话中的工具调用记录，建议开启新会话。", fontFamily = JetBrainsMono, fontSize = 12.sp, color = Ink, lineHeight = 18.sp) },
            confirmButton = { TextButton(onClick = { val name = pendingModelIdx; pendingModelIdx = null; name?.let { onSwitchModel(it) } }) { Text("切换", fontFamily = JetBrainsMono, color = Red) } },
            dismissButton = { TextButton(onClick = { pendingModelIdx = null }) { Text("取消", fontFamily = JetBrainsMono, color = Sub) } },
            containerColor = Bg
        )
    }
}

@Composable
private fun WorkspaceQuickStrip(
    linuxReady: Boolean,
    onOpenTerminal: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val xc = LocalHsuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 52.dp)
            .background(xc.bgElevated, RoundedCornerShape(10.dp))
            .border(1.dp, xc.border, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (linuxReady) xc.green else xc.yellow))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (linuxReady) "Ubuntu 工作区" else "免 Root 工作区", style = MaterialTheme.typography.labelLarge, color = xc.ink)
            Text(
                if (linuxReady) WorkspaceRuntime.detail() else "Android Shell · 安装 Ubuntu 后可使用 apt 与开发工具",
                style = MaterialTheme.typography.bodySmall, color = xc.sub, maxLines = 1
            )
        }
        IconButton(onClick = onOpenTerminal, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Terminal, contentDescription = "打开终端", tint = xc.green)
        }
        IconButton(onClick = onOpenWorkspace, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Tune, contentDescription = "配置工作区", tint = xc.sub)
        }
    }
}

/**
 * Slash-command menu shown while the input begins with '/'.
 * Built-in items: `/compact`. Every user-defined skill is also listed and
 * expands into its prompt when selected.
 */
@Composable
private fun SlashCommandMenu(
    query: String,
    skillNames: List<String>,
    onCompact: () -> Unit,
    onSelectSkill: (String) -> Unit,
    onClose: () -> Unit
) {
    val xc = LocalHsuColors.current
    data class Item(val label: String, val desc: String, val onClick: () -> Unit)
    val q = query.lowercase()
    val builtins = listOf(
        Item("/compact", "总结当前对话为摘要，释放上下文") { onCompact() }
    )
    val skillItems = skillNames.map { s -> Item("/skill: $s", "注入技能 '$s' 的内容", { onSelectSkill(s) }) }
    val filtered = (builtins + skillItems).filter { q.isBlank() || it.label.lowercase().contains(q) }

    AnimatedVisibility(
        visible = filtered.isNotEmpty() || q.isBlank(),
        enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { it / 2 }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp)
                .background(xc.bgElevated, RoundedCornerShape(12.dp))
                .border(1.dp, xc.border, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Slash 命令 · ${filtered.size} 项",
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.sub,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.sub,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClose() }
                )
            }
            if (filtered.isEmpty()) {
                Text(
                    "无匹配命令。可用: /compact, /skill: <name>",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.faint,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            filtered.take(6).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { item.onClick() }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink)
                        Text(item.desc, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                    }
                    Text("↵", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.faint)
                }
            }
        }
    }
}

/** 「+」卡片上排的方块动作(图标 + 标签)。 */
@Composable
private fun PlusAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color, sub: Color, onClick: () -> Unit) {
    Column(
        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontFamily = JetBrainsMono, color = sub)
    }
}

/** 「+」卡片下排的一行(图标 + 标题 + 说明/状态 + 可选开关点)。 */
@Composable
private fun PlusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String, ink: Color, subC: Color, toggled: Boolean?, onClick: () -> Unit) {
    val green = LocalHsuColors.current.green
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = if (toggled == true) green else ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = ink)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = subC, maxLines = 2)
        }
        if (toggled != null) {
            Switch(checked = toggled, onCheckedChange = { onClick() })
        }
    }
}

/** 技能选择对话框:列出可用技能,点选插入。 */
@Composable
private fun SkillPickerDialog(skillNames: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val xc = LocalHsuColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择技能", fontSize = 14.sp, fontFamily = JetBrainsMono, color = xc.ink) },
        text = {
            if (skillNames.isEmpty()) {
                Text("暂无技能(可在 设置→Skills 管理)", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.faint)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    skillNames.forEach { name ->
                        Text(name, fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(name) }
                                .padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭", fontFamily = JetBrainsMono, color = xc.sub) } },
        containerColor = xc.bg
    )
}

/** 可展开的统计小卡片:折叠态 = token 一行 + 箭头;展开态 = 子智能体 token + 调用占比。 */
@Composable
private fun ExpandableStatsBar(stats: TokenStats, model: String, chatState: ChatStateLike) {
    val xc = LocalHsuColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (stats.hasData) TokenStatsBar(stats, model)
                else Text("统计", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
            }
            Text(if (expanded) " ▾" else " ▸", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        }
        if (expanded) {
            // 调用占比(从内存消息统计)。
            val counts = remember(chatState.messages.size, expanded) {
                val m = HashMap<String, Int>()
                for (msg in chatState.messages) {
                    if (msg.role != "tool") continue
                    val name = try {
                        val j = org.json.JSONObject(msg.content)
                        if (j.optBoolean("__tool_call__", false)) j.optString("tool_name", "") else ""
                    } catch (_: Exception) { "" }
                    if (name.isBlank()) continue
                    val cat = when {
                        name in listOf("web_search", "web_fetch", "web_search_batch") -> "网络"
                        name in listOf("file_read", "file_write", "file_edit", "edit", "multi_edit", "list_dir", "grep", "glob") -> "文件"
                        name in listOf("shell_exec", "su_exec", "code_exec", "env_exec", "shizuku_exec") -> "终端"
                        name in listOf("invoke_skill", "skill_manage") -> "技能"
                        name in listOf("dispatch_agents", "wolfpack_run") -> "子智能体"
                        name == "agent_plan" -> "计划"
                        name in listOf("recall_memory", "save_memory") -> "记忆"
                        name.contains("__") -> "MCP"
                        else -> "其他"
                    }
                    m[cat] = (m[cat] ?: 0) + 1
                }
                m
            }
            val total = counts.values.sum()
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .background(xc.bgElevated, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                Text("子智能体:${AgentStats.subAgentRuns} 次 · ${formatCount(AgentStats.subAgentTokens)} tokens",
                    fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                Text("调用占比(共 $total 次)", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
                if (total == 0) {
                    Text("暂无工具调用", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint, modifier = Modifier.padding(top = 4.dp))
                } else {
                    counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            Text(cat, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
                            Text("$cnt 次 · ${cnt * 100 / total}%", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                        }
                    }
                }
            }
        }
    }
}

/** Compact token accounting strip. Renders quietly above the input. */
@Composable
private fun TokenStatsBar(stats: TokenStats, model: String = "") {
    val xc = LocalHsuColors.current
    val cachePct = (stats.cacheHitRatio * 100).toInt()
    val cacheColor = when {
        stats.cacheHitRatio > 0.5f -> xc.green
        stats.cacheHitRatio > 0.2f -> xc.yellow
        else -> xc.sub
    }
    val cost = Pricing.costRmb(stats, model)
    val line = buildString {
        append("in ")
        append(formatCount(stats.prompt))
        append(" · out ")
        append(formatCount(stats.completion))
        if (stats.cacheHit + stats.cacheMiss > 0) {
            append(" · cache ")
            append(cachePct)
            append("%")
        }
        append(" · Σ ")
        append(formatCount(stats.total))
        // 缓存感知的人民币成本(未知模型不显示)。
        if (cost != null) {
            append(" · ")
            append(Pricing.formatRmb(cost))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(line, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub,
            modifier = Modifier.weight(1f))
        if (stats.cacheHit + stats.cacheMiss > 0) {
            Text("●", fontSize = 9.sp, color = cacheColor)
        }
    }
}

private fun formatCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 10_000 -> "%.1fk".format(n / 1000.0)
    n < 1_000_000 -> "${n / 1000}k"
    else -> "%.1fM".format(n / 1_000_000.0)
}

// 上下文圆环配色:绿(少)→蓝(中)→黄(接近满)→红(几乎满),分段线性插值。
private val RingGreen = Color(0xFF7BE0A4)
private val RingBlue = Color(0xFF4FA3FF)
private val RingYellow = Color(0xFFF2C14E)
private val RingRed = Color(0xFFE5484D)

private fun ringColor(ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return when {
        r <= 0.45f -> lerp(RingGreen, RingBlue, r / 0.45f)
        r <= 0.75f -> lerp(RingBlue, RingYellow, (r - 0.45f) / 0.30f)
        else -> lerp(RingYellow, RingRed, (r - 0.75f) / 0.25f)
    }
}

/**
 * 上下文占用圆环:随占用比例填满,颜色从绿→蓝→黄→红渐变。窗口未知时显示为一圈淡描边 + 「?」。
 * 点击弹出统计卡片。
 */
@Composable
private fun ContextRing(usage: ContextUsage, onClick: () -> Unit) {
    val xc = LocalHsuColors.current
    val ratio = usage.ratio
    // 平滑动画到目标占用(修「刷新不及时」时的跳变观感)。
    val animated by animateFloatAsState(
        targetValue = if (usage.known) ratio else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "ctxRing"
    )
    val fg = ringColor(if (usage.known) ratio else 0f)
    Box(
        Modifier.size(48.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 3.dp.toPx()
            // 轨道
            drawArc(
                color = xc.border,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 已占用
            if (usage.known && animated > 0f) {
                drawArc(
                    color = fg,
                    startAngle = -90f, sweepAngle = animated * 360f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            if (usage.known) "${(ratio * 100).toInt()}" else "?",
            fontSize = 9.sp, fontFamily = JetBrainsMono,
            color = if (usage.known) fg else xc.faint
        )
    }
}

/**
 * 点圆环弹出的统计卡片(收窄):上下文占用 + 总 token + 子智能体 token + 工具/skill/MCP 调用占比(不同颜色)。
 */
@Composable
private fun ContextStatsCard(usage: ContextUsage, stats: TokenStats, model: String, chatState: ChatStateLike, onClose: () -> Unit) {
    val xc = LocalHsuColors.current
    val ringC = ringColor(usage.ratio)
    // 调用占比(从内存消息统计)。
    val counts = remember(chatState.messages.size) {
        val m = HashMap<String, Int>()
        for (msg in chatState.messages) {
            if (msg.role != "tool") continue
            val name = try {
                val j = org.json.JSONObject(msg.content)
                if (j.optBoolean("__tool_call__", false)) j.optString("tool_name", "") else ""
            } catch (_: Exception) { "" }
            if (name.isBlank()) continue
            val cat = when {
                name in listOf("web_search", "web_fetch", "web_search_batch") -> "网络"
                name in listOf("file_read", "file_write", "file_edit", "edit", "multi_edit", "list_dir", "grep", "glob") -> "文件"
                name in listOf("shell_exec", "su_exec", "code_exec", "env_exec", "shizuku_exec") -> "终端"
                name in listOf("invoke_skill", "skill_manage") -> "技能"
                name in listOf("dispatch_agents", "wolfpack_run") -> "子智能体"
                name == "agent_plan" -> "计划"
                name in listOf("recall_memory", "save_memory") -> "记忆"
                name.contains("__") -> "MCP"
                else -> "其他"
            }
            m[cat] = (m[cat] ?: 0) + 1
        }
        m
    }
    val total = counts.values.sum()
    val catColors = mapOf(
        "网络" to RingBlue, "文件" to RingGreen, "终端" to Color(0xFF9B87F5),
        "技能" to RingYellow, "子智能体" to Color(0xFFE58F65), "计划" to Color(0xFF57C7D4),
        "记忆" to Color(0xFFC77DBB), "MCP" to Color(0xFF6FBF73), "其他" to xc.faint
    )
    Column(
        Modifier.width(232.dp)
            .background(xc.bgElevated, RoundedCornerShape(14.dp))
            .border(1.dp, xc.border, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("上下文", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
            Text("✕", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() })
        }
        Spacer(Modifier.height(8.dp))
        // 上下文占用
        if (usage.known) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("占用", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.weight(1f))
                Text("${(usage.ratio * 100).toInt()}%", fontSize = 11.sp, fontFamily = JetBrainsMono, color = ringC)
            }
            Spacer(Modifier.height(3.dp))
            // 进度条
            Box(Modifier.fillMaxWidth().height(5.dp).background(xc.border, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(usage.ratio).height(5.dp).background(ringC, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(3.dp))
            Text("${formatCount(usage.usedTokens)} / ${formatCount(usage.windowTokens)} tokens",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        } else {
            Text("上下文窗口未配置(设置→上下文压缩→上下文长度)",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
            Text("当前上下文 ≈ ${formatCount(usage.usedTokens)} tokens",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(xc.border))
        Spacer(Modifier.height(8.dp))
        // 总 token
        Text("总用量 Σ ${formatCount(stats.total)}", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text("in ${formatCount(stats.prompt)} · out ${formatCount(stats.completion)}" +
            (if (stats.cacheHit + stats.cacheMiss > 0) " · cache ${(stats.cacheHitRatio * 100).toInt()}%" else ""),
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
        val cost = Pricing.costRmb(stats, model)
        if (cost != null) Text("成本 ${Pricing.formatRmb(cost)}", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)

        Spacer(Modifier.height(8.dp))
        // 子智能体
        Text("子智能体 ${AgentStats.subAgentRuns} 次 · ${formatCount(AgentStats.subAgentTokens)} tokens",
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)

        Spacer(Modifier.height(8.dp))
        Text("调用占比(共 $total 次)", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        if (total == 0) {
            Text("暂无工具调用", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint, modifier = Modifier.padding(top = 3.dp))
        } else {
            // 颜色分段条
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                    Box(Modifier.fillMaxHeight().weight(cnt.toFloat()).background(catColors[cat] ?: xc.faint))
                }
            }
            Spacer(Modifier.height(6.dp))
            counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(catColors[cat] ?: xc.faint))
                    Spacer(Modifier.width(6.dp))
                    Text(cat, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
                    Text("$cnt · ${cnt * 100 / total}%", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                }
            }
        }
    }
}

/** Permission controls for the chat composer, matching the three approval levels. */
@Composable
private fun ModeCard(
    permissionMode: com.hsucode.security.PermissionMode,
    collabMode: Boolean,
    onPick: (com.hsucode.security.PermissionMode, Boolean) -> Unit
) {
    val xc = LocalHsuColors.current
    val ask = com.hsucode.security.PermissionMode.ASK
    val autoApproveRisk = com.hsucode.security.PermissionMode.AUTO_APPROVE_RISK
    val plan = com.hsucode.security.PermissionMode.PLAN
    val allowAll = com.hsucode.security.PermissionMode.ALLOW_ALL
    Column(
        Modifier.widthIn(min = 280.dp, max = 320.dp)
            .background(xc.bgElevated, RoundedCornerShape(8.dp))
            .border(1.dp, xc.border, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text("权限控制", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(8.dp))
        PermissionAccessRow(
            title = "请求批准",
            subtitle = "编辑文件、执行命令或联网前请求确认",
            active = !collabMode && permissionMode == ask,
            color = xc.sub,
        ) { onPick(ask, false) }
        Spacer(Modifier.height(6.dp))
        PermissionAccessRow(
            title = "帮我批准",
            subtitle = "普通操作自动执行，仅检测到风险时询问",
            active = !collabMode && permissionMode == autoApproveRisk,
            color = xc.green,
        ) { onPick(autoApproveRisk, false) }
        Spacer(Modifier.height(6.dp))
        PermissionAccessRow(
            title = "完全访问权限",
            subtitle = "自动访问文件和网络；致命破坏操作仍会拦截",
            active = !collabMode && permissionMode == allowAll,
            color = xc.red,
        ) { onPick(allowAll, false) }

        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(0.5.dp).background(xc.border))
        Text("工作模式", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessChip("计划", !collabMode && permissionMode == plan, xc.green, xc) { onPick(plan, false) }
            AccessChip("协作", collabMode, xc.green, xc) {
                onPick(if (permissionMode == plan) ask else permissionMode, true)
            }
        }
    }
}

@Composable
private fun PermissionAccessRow(
    title: String,
    subtitle: String,
    active: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val xc = LocalHsuColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) color.copy(alpha = 0.13f) else xc.bg)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) color else xc.ink)
            Text(subtitle, fontSize = 11.sp, color = xc.sub)
        }
        if (active) Text("✓", fontSize = 18.sp, color = color)
    }
}

@Composable
private fun ModeRowSimple(label: String, sub: String, active: Boolean, xc: HsuColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink)
            Text(sub, fontSize = 9.sp, fontFamily = JetBrainsMono, color = xc.sub)
        }
        if (active) Text("✓", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.green)
    }
}

@Composable
private fun AccessChip(label: String, active: Boolean, activeColor: Color, xc: HsuColors, onClick: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, color = if (active) activeColor else xc.sub) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = activeColor.copy(alpha = 0.14f),
            selectedLabelColor = activeColor
        )
    )
}

/** Calm empty state inspired by RikkaHub: useful actions appear before the first message. */
@Composable
private fun WelcomePanel(
    currentModel: String,
    providerName: String = "",
    hasWorkspace: Boolean = false,
    onPrompt: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
) {
    val colors = LocalHsuColors.current
    val prompts = listOf(
        "分析一个代码问题",
        "整理当前项目结构",
        "检查工作区状态",
        "帮我制定执行计划"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.green, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(16.dp))
        Text("今天想完成什么？", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Text(
            when {
                currentModel.isBlank() -> "还没有配置模型"
                providerName.isBlank() -> currentModel
                else -> "$providerName · $currentModel"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.sub,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (currentModel.isBlank()) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) {
                Text("配置模型")
            }
        } else if (!hasWorkspace) {
            TextButton(onClick = onOpenWorkspace, modifier = Modifier.padding(top = 4.dp)) {
                Text("配置工作区", color = colors.green)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            prompts.take(2).forEach { prompt ->
                AssistChip(
                    onClick = { onPrompt(prompt) },
                    label = { Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            prompts.drop(2).forEach { prompt ->
                AssistChip(
                    onClick = { onPrompt(prompt) },
                    label = { Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Consolidated execution state for goal progress, live plan, and agent status. */
@Composable
private fun ExecutionStrip(
    isGoalSession: Boolean,
    goalStatusCode: String,
    goalLiveText: String,
    goalRunning: Boolean,
    planState: PlanState?,
    statusLine: String,
    isRunning: Boolean,
    onStop: () -> Unit
) {
    val colors = LocalHsuColors.current
    val planVisible = planState?.visible == true && planState.steps.isNotEmpty()
    val visible = isGoalSession || planVisible || statusLine.isNotBlank() || isRunning
    if (!visible) return
    var expanded by remember(planState?.title, isGoalSession) { mutableStateOf(planVisible) }
    val failed = goalStatusCode == "failed" || statusLine.startsWith("✗")
    val completed = goalStatusCode == "achieved" || (planVisible && planState?.doneCount() == planState?.totalCount())
    val title = when {
        failed -> "执行遇到问题"
        goalRunning || isRunning -> "正在执行"
        completed -> "执行完成"
        isGoalSession -> "目标任务"
        planVisible -> planState?.title.orEmpty().ifBlank { "执行计划" }
        else -> "执行状态"
    }
    val detail = when {
        goalLiveText.isNotBlank() -> goalLiveText
        statusLine.isNotBlank() -> statusLine
        planVisible -> "${planState?.doneCount() ?: 0}/${planState?.totalCount() ?: 0} 步"
        else -> "等待任务"
    }

    Column(Modifier.fillMaxWidth().background(colors.bgElevated)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(enabled = planVisible || isGoalSession || statusLine.isNotBlank()) { expanded = !expanded }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                goalRunning || isRunning -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.green
                )
                failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(20.dp))
                completed -> Icon(Icons.Outlined.CheckCircle, null, tint = colors.green, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Outlined.Info, null, tint = colors.sub, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = colors.ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (failed) colors.red else colors.sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (goalRunning) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, contentDescription = "停止目标", tint = colors.red)
                }
            }
            if (planVisible || isGoalSession || statusLine.isNotBlank()) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.sub
                )
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (isGoalSession && goalLiveText.isNotBlank()) {
                    Text(goalLiveText, style = MaterialTheme.typography.bodySmall, color = colors.sub,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (planVisible && planState != null) PlanCard(planState)
            }
        }
        HorizontalDivider(color = colors.divider)
    }
}

/** Goal/Work 模式横幅:显示目标状态 + 运行中可停止。 */
@Composable
private fun GoalBanner(statusCode: String, liveText: String, running: Boolean, onStop: () -> Unit) {
    val xc = LocalHsuColors.current
    val (dot, label) = when {
        running || statusCode == "running" -> xc.yellow to (liveText.ifBlank { "执行中…" })
        statusCode == "achieved" -> xc.green to "✓ 目标已达成"
        statusCode == "failed" -> xc.red to "✗ 目标未达成"
        else -> xc.faint to "输入一个目标,HSUCODE 会自主执行、裁判验收,完成后通知你"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .background(xc.bgElevated, RoundedCornerShape(10.dp))
            .border(1.dp, xc.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Goal 模式", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
            Text(label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (running || statusCode == "running") {
            Text("停止", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.red,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onStop() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** MCP 服务器选择对话框:列出已配置服务器,点选在输入框生成 @服务器 引用;底部可进管理页。 */
@Composable
private fun McpPickerDialog(mcpNames: List<String>, onPick: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val xc = LocalHsuColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 MCP 服务器", fontSize = 14.sp, fontFamily = JetBrainsMono, color = xc.ink) },
        text = {
            if (mcpNames.isEmpty()) {
                Text("暂无 MCP 服务器(点下方「管理」去添加)", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.faint)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    mcpNames.forEach { name ->
                        Text("@$name", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(name) }
                                .padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onManage) { Text("管理", fontFamily = JetBrainsMono, color = xc.green) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭", fontFamily = JetBrainsMono, color = xc.sub) } },
        containerColor = xc.bg
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatState.MessageUi, isStreamingMessage: Boolean = false, onRetry: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    val isError = msg.content.startsWith("✗ ")
    val xc = LocalHsuColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    val roleLabel = when {
        isUser -> "you"
        isTool -> "❯ tool"
        else -> "hsucode"
    }
    val roleColor = when {
        isUser -> Sub
        isError -> Red
        isTool -> Green
        else -> Green
    }
    val contentColor = when {
        isError -> Red
        isTool -> Faint
        else -> Ink
    }
    val context = LocalContext.current
    var showMenu by remember(msg.id) { mutableStateOf(false) }

    // Blinking cursor while this specific assistant is streaming
    val cursorTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 0.15f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp),
        // 用户消息靠右,AI/工具消息靠左。
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Reasoning section (message-level, inside bubble, collapsible)
        ReasoningFoldable(msg, isCurrentStreaming = isStreamingMessage)

        // Keep long-press actions off the body so Android can begin a partial text selection.
        Row(
            modifier = Modifier.combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
                onLongClick = { showMenu = true }
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                roleLabel,
                fontSize = 10.sp,
                fontFamily = JetBrainsMono,
                color = roleColor
            )
            if (isError && onRetry != null) {
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRetry() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, "重试", tint = Sub, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("重试", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub)
                }
            }
        }

        // 气泡。工具消息不套 —— 它是折叠的技术输出,套上反而像有人在说话。
        // 宽度限到 88% 并留出对侧空白:占满整行的话左右之分就看不出来了。
        val bubbleModifier = if (isTool) Modifier else Modifier
            .fillMaxWidth(0.88f)
            .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start)
            .clip(
                RoundedCornerShape(
                    topStart = 12.dp, topEnd = 12.dp,
                    // 靠自己那一侧的下角收窄,气泡才有指向感
                    bottomStart = if (isUser) 12.dp else 3.dp,
                    bottomEnd = if (isUser) 3.dp else 12.dp
                )
            )
            .background(if (isUser) xc.activeBg else xc.bgElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp)

        Box(bubbleModifier) {
        // Content (Markdown for assistant, plain text for user/tool)
        if (msg.role == "assistant") {
            if (msg.content.isNotEmpty()) {
                Column {
                    MarkdownContent(msg.content)
                    if (isStreamingMessage) {
                        Text(
                            "▊",
                            fontSize = 13.sp,
                            fontFamily = JetBrainsMono,
                            color = Ink,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                }
            } else {
                Text(
                    "▊",
                    fontSize = 13.sp,
                    fontFamily = JetBrainsMono,
                    color = Ink,
                    modifier = Modifier.alpha(cursorAlpha)
                )
            }
        } else {
            SelectionContainer {
                Text(
                    msg.content.ifEmpty {
                        if (isTool) "(empty)" else ""
                    },
                    fontSize = if (isTool) 11.sp else 13.sp,
                    fontFamily = if (isTool) JetBrainsMono else FontFamily.Default,
                    color = contentColor,
                    lineHeight = if (isTool) 16.sp else 20.sp
                )
            }
        }
        }   // 气泡 Box 结束

        // 常驻操作行。工具消息不给(它自己有展开/折叠),流式进行中也不给。
        if (!isTool && !isStreamingMessage) {
            MessageActionsRow(
                content = msg.content,
                onRegenerate = onRegenerate,
                alignEnd = isUser
            )
        }
    }
    // Subtle separator between messages
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(0.5.dp).background(Border))

    if (showMenu) {
        MessageActionSheet(
            content = msg.content,
            reasoning = msg.reasoning,
            canDelete = onDelete != null,
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("hsucode", msg.content))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                showMenu = false
            },
            onCopyReasoning = if (msg.reasoning.isNotBlank()) {
                {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("hsucode-think", msg.reasoning))
                    Toast.makeText(context, "已复制思考过程", Toast.LENGTH_SHORT).show()
                    showMenu = false
                }
            } else null,
            onDelete = if (onDelete != null) { { onDelete(); showMenu = false } } else null,
            onDismiss = { showMenu = false }
        )
    }
}

/**
 * 消息下方常驻的操作行(复制 / 重答)。
 *
 * 之前这些操作只藏在长按菜单里,而且交错时间线走的是 AgentTurnBlock,那条路根本没接
 * 长按菜单 —— 等于绝大多数回复都没有任何可操作入口。所以改成常驻小字按钮,两处都用它。
 * 样式压到最低(10sp、次要色),不抢正文。
 */
@Composable
fun MessageActionsRow(
    content: String,
    onRegenerate: (() -> Unit)? = null,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (content.isBlank() && onRegenerate == null) return
    val xc = LocalHsuColors.current
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (content.isNotBlank()) {
            Text(
                "复制",
                fontSize = 11.sp, fontFamily = FontFamily.Default, color = xc.sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("hsucode", content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        if (onRegenerate != null) {
            Text(
                "重答",
                fontSize = 11.sp, fontFamily = FontFamily.Default, color = xc.sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRegenerate() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageActionSheet(
    content: String,
    reasoning: String,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onCopyReasoning: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val xc = LocalHsuColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作", fontFamily = JetBrainsMono, color = xc.ink, fontSize = 14.sp) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCopy() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("复制内容", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.ink)
                }
                if (onCopyReasoning != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onCopyReasoning() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("复制思考过程", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.ink)
                    }
                }
                if (onDelete != null && canDelete) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDelete() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("删除这条消息", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.red)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = xc.sub, fontFamily = JetBrainsMono) } },
        containerColor = xc.bg
    )
}

/**
 * In-flow confirmation card rendered inside the message list.
 * Style: terminal aesthetic, thin border, rounded 8dp, three horizontal buttons.
 */
@Composable
private fun ConfirmCard(
    command: String,
    isIrreversible: Boolean,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit
) {
    val xc = LocalHsuColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, Color(0x1A1A1A17), RoundedCornerShape(8.dp))
            .background(Bg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Header
        Text(
            "hsucode 想执行",
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
            color = Sub
        )
        Spacer(Modifier.height(6.dp))
        // Command line
        Text(
            "  ❯ $command",
            fontSize = 12.sp,
            fontFamily = JetBrainsMono,
            color = Green
        )
        // Risk warning (only for irreversible operations)
        if (isIrreversible) {
            Spacer(Modifier.height(4.dp))
            Text(
                "  ⚠ 不可逆操作，请谨慎确认",
                fontSize = 10.sp,
                fontFamily = JetBrainsMono,
                color = Red
            )
        }
        Spacer(Modifier.height(10.dp))
        // Three buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "拒绝",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Sub,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDeny() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(Modifier.width(0.5.dp).height(24.dp).background(Border))
            Text(
                "仅本次",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Ink,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAllowOnce() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(Modifier.width(0.5.dp).height(24.dp).background(Border))
            Text(
                "总是允许",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Green,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAlwaysAllow() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
