package com.hsucode.app

import android.util.Log
import com.hsucode.core.AgentCore
import com.hsucode.core.AgentState
import com.hsucode.core.Tool
import com.hsucode.core.ToolRegistry
import com.hsucode.core.ToolResult
import com.hsucode.data.AppDatabase
import com.hsucode.data.CommandRoomEventEntity
import com.hsucode.data.CommandRoomRunEntity
import com.hsucode.data.SubAgentEntity
import com.hsucode.provider.OpenAiClient
import com.hsucode.security.Reversibility
import com.hsucode.security.SecurityGate
import com.hsucode.security.ToolConfirmResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体调度(主脑 → 专职子智能体)。
 *
 * `dispatch_agents` 接收一组 {agent, task} 分配:把一个大任务【同时】拆给不同类型的子智能体并行处理,
 * 各自跑在**隔离** AgentCore 上,用**各自专属的技能与工具**;全部完成后把各自结论汇总回主脑。
 *
 * 关键:每个子智能体只被注入【它这一类型的专属技能】,让它据任务从自己这套里选(而非盲目从全量技能挑);
 * 工具也只给它类型白名单里的那些。
 */
class SubAgentTool(
    private val mainRegistry: ToolRegistry,
    private val openAiClient: OpenAiClient,
    private val database: AppDatabase,
    private val securityGate: SecurityGate?,
    private val scene: SubAgentSceneState? = null   // 「指挥室」像素动画状态(可空)
) : Tool {

    companion object {
        private const val TAG = "SubAgentTool"
        private const val MAX_CONCURRENT = 4
        // 网络研究常常需要多轮抓取,3 分钟太短会"搜到超时";放宽到 10 分钟。
        private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L
        private const val ACTIVITY_THROTTLE_MS = 120L
        private val READONLY_DEFAULT = listOf("file_read", "list_dir", "grep", "glob")
        private const val INVOKE_SKILL = "invoke_skill"
    }

    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerSemaphore = Semaphore(MAX_CONCURRENT)
    private val activeCores = ConcurrentHashMap<String, AgentCore>()
    private val cancelledWorkers = ConcurrentHashMap.newKeySet<String>()

    init {
        scene?.bindActions(
            onCancelWorker = ::cancelWorker,
            onRetryWorker = ::retryWorker,
            onStopAll = ::stopAll
        )
    }

    override val name = "dispatch_agents"
    override val description =
        "把一个大任务【同时】拆给多个专职子智能体并行处理,各用各的专属技能/工具,最后汇总结论回你(主脑)。" +
        "先用 list_sub_agents 或看下方清单了解有哪些子智能体类型。传 assignments=[{agent:\"类型名\", task:\"这个子智能体要做的事\"}...]。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assignments", JSONObject().apply {
                put("type", "array")
                put("description", "分配数组,每项 {agent:子智能体类型名, task:要它做的事}")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("agent", JSONObject().put("type", "string"))
                        put("task", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray(listOf("agent", "task")))
                })
            })
        })
        put("required", JSONArray(listOf("assignments")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // 兼容:executeJson 未命中时从压平的字符串解析。
        val raw = params["assignments"] ?: return ToolResult.Error("缺少 assignments")
        return runAssignments(try { JSONArray(raw) } catch (e: Exception) { return ToolResult.Error("assignments 不是合法 JSON 数组") })
    }

    override suspend fun executeJson(args: JSONObject): ToolResult {
        val arr = coerceAssignments(args) ?: return ToolResult.Error(
            "dispatch_agents 需要 assignments 数组，形如 " +
                "assignments=[{\"agent\":\"子智能体类型名\",\"task\":\"要它做的事\"}]。" +
                "你这次传的是: ${args.toString().take(200)}"
        )
        return runAssignments(arr)
    }

    /** Runs one configured agent from the agent center using the exact same safety path as model dispatch. */
    suspend fun runDirect(agentName: String, task: String): ToolResult {
        if (agentName.isBlank() || task.isBlank()) return ToolResult.Error("智能体名称和任务不能为空")
        return runAssignments(JSONArray().put(JSONObject().apply {
            put("agent", agentName.trim())
            put("task", task.trim())
        }))
    }

    /**
     * 把模型给的各种写法都收敛成 assignments 数组。
     *
     * 【为什么要这么宽容】原来只认 `optJSONArray("assignments")`，别的形状一律回一句
     * 「缺少 assignments 数组」——这句话对模型没有信息量（它以为自己传了），于是原样重试，
     * 连错三次被防空转刹车掐掉，整轮分派作废。而实际上这几种写法的意图都毫无歧义：
     *  - 只派一个人时写成对象 `{agent, task}` 而不是只有一项的数组
     *  - 键名写成 tasks / agents（描述里两种词都出现过，模型记混很正常）
     *  - 整个数组被序列化成字符串 `"[{...}]"`（部分供应商的函数调用会这样吐）
     */
    private fun coerceAssignments(args: JSONObject): JSONArray? {
        for (key in listOf("assignments", "tasks", "agents", "assignment")) {
            args.optJSONArray(key)?.let { return it }
            args.optJSONObject(key)?.let { return JSONArray().put(it) }
            val s = args.optString(key, "").trim()
            if (s.startsWith("[")) runCatching { JSONArray(s) }.getOrNull()?.let { return it }
            if (s.startsWith("{")) runCatching { JSONObject(s) }.getOrNull()?.let { return JSONArray().put(it) }
        }
        // 顶层就直接是 {agent, task}(一个人的时候模型很爱这么写)
        if (args.optString("agent").isNotBlank() && args.optString("task").isNotBlank()) {
            return JSONArray().put(args)
        }
        return null
    }

    private suspend fun runAssignments(arr: JSONArray): ToolResult {
        if (arr.length() == 0) return ToolResult.Error("assignments 为空")
        val items = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optString("agent") to it.optString("task") }
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (items.isEmpty()) return ToolResult.Error("assignments 无有效项(需 agent + task)")

        val sceneWorkers = scene?.begin(items)
        val runId = sceneWorkers?.firstOrNull()?.runId ?: UUID.randomUUID().toString()
        val workerIds = items.indices.map { sceneWorkers?.get(it)?.workerRunId ?: "$runId:$it" }
        recordRun(CommandRoomRunEntity(runId, System.currentTimeMillis(), assignmentCount = items.size))
        items.forEachIndexed { index, (agentName, task) ->
            recordEvent(runId, workerIds[index], agentName, "assigned", SubAgentSceneState.Status.QUEUED, task)
        }

        val out = JSONArray()
        var outcome = "interrupted"
        try {
            supervisorScope {
                val jobs = items.mapIndexed { index, (agentName, task) ->
                    async(Dispatchers.IO) {
                        workerSemaphore.withPermit {
                            runOne(runId, workerIds[index], agentName, task)
                        }
                    }
                }
                jobs.forEach { out.put(it.await()) }
            }
            val itemOutcomes = (0 until out.length()).map { out.optJSONObject(it)?.optString("outcome").orEmpty() }
            outcome = when {
                itemOutcomes.all { it == "succeeded" } -> "completed"
                itemOutcomes.any { it == "failed" || it == "timed_out" } -> "failed"
                itemOutcomes.any { it == "cancelled" } -> "cancelled"
                else -> "failed"
            }
            return ToolResult.Success(out.toString(2))
        } catch (e: CancellationException) {
            workerIds.forEach { workerId ->
                cancelledWorkers.add(workerId)
                activeCores[workerId]?.stop()
                if (scene?.worker(workerId)?.status?.isActive == true) {
                    scene.fail(workerId, SubAgentSceneState.Status.CANCELLED, "上层任务已停止")
                }
            }
            outcome = "cancelled"
            throw e
        } finally {
            scene?.finishRun()
            finishHistoryRun(runId, outcome)
        }
    }

    private suspend fun runOne(
        runId: String,
        workerRunId: String,
        agentName: String,
        task: String
    ): JSONObject {
        if (cancelledWorkers.remove(workerRunId)) {
            scene?.fail(workerRunId, SubAgentSceneState.Status.CANCELLED, "已取消")
            recordEvent(runId, workerRunId, agentName, "cancelled", SubAgentSceneState.Status.CANCELLED, "执行前取消")
            return result(agentName, false, "已取消", "cancelled", workerRunId)
        }
        scene?.setStatus(workerRunId, SubAgentSceneState.Status.PREPARING, "加载智能体配置")
        val def = database.subAgentDao().getByName(agentName)
        if (def == null) {
            val error = "未找到子智能体类型「$agentName」(用 list_sub_agents 查看)"
            scene?.fail(workerRunId, SubAgentSceneState.Status.FAILED, error)
            recordEvent(runId, workerRunId, agentName, "failed", SubAgentSceneState.Status.FAILED, error)
            return result(agentName, false, error, "failed", workerRunId)
        }
        if (!def.enabled) {
            val error = "智能体「$agentName」已停用，请在智能体中心启用后再派发"
            scene?.fail(workerRunId, SubAgentSceneState.Status.FAILED, error)
            recordEvent(runId, workerRunId, agentName, "failed", SubAgentSceneState.Status.FAILED, error)
            return result(agentName, false, error, "failed", workerRunId)
        }
        scene?.setStatus(workerRunId, SubAgentSceneState.Status.RUNNING, "分析任务")
        recordEvent(runId, workerRunId, agentName, "started", SubAgentSceneState.Status.RUNNING, task)
        var core: AgentCore? = null
        return try {
            withTimeout(TASK_TIMEOUT_MS) {
                val runtimeCore = buildCore(def, workerRunId, runId)
                core = runtimeCore
                activeCores[workerRunId] = runtimeCore
                runtimeCore.clearHistory()
                val buf = StringBuilder()
                coroutineScope {
                    var lastUiUpdate = 0L
                    val collector = launch {
                        runtimeCore.tokenFlow.collect {
                            buf.append(it)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= ACTIVITY_THROTTLE_MS) {
                                scene?.setActivity(workerRunId, buf.toString().takeLast(600))
                                lastUiUpdate = now
                            }
                        }
                    }
                    runtimeCore.onToolBlock = { action ->
                        if (action is com.hsucode.core.ToolBlockAction.PushCall) {
                            val activity = "调用工具: ${action.toolName} ${action.arguments.take(100)}"
                            scene?.setTool(workerRunId, action.toolName, activity)
                            controlScope.launch {
                                recordEvent(runId, workerRunId, agentName, "tool_call", SubAgentSceneState.Status.RUNNING, activity)
                            }
                        }
                    }
                    val job = runtimeCore.run(buildTaskPrompt(task), this)
                    job.join()
                    collector.cancelAndJoin()
                }
                if (cancelledWorkers.remove(workerRunId) || runtimeCore.state.value is AgentState.Interrupted) {
                    val message = "用户已取消"
                    scene?.fail(workerRunId, SubAgentSceneState.Status.CANCELLED, message)
                    recordEvent(runId, workerRunId, agentName, "cancelled", SubAgentSceneState.Status.CANCELLED, message)
                    return@withTimeout result(agentName, false, message, "cancelled", workerRunId)
                }
                val coreError = (runtimeCore.state.value as? AgentState.Error)?.message
                if (coreError != null) {
                    scene?.fail(workerRunId, SubAgentSceneState.Status.FAILED, coreError)
                    recordEvent(runId, workerRunId, agentName, "failed", SubAgentSceneState.Status.FAILED, coreError)
                    return@withTimeout result(agentName, false, coreError, "failed", workerRunId)
                }
                val text = buf.toString().ifBlank { "(无输出)" }.take(3000)
                scene?.complete(workerRunId, text)
                recordEvent(runId, workerRunId, agentName, "completed", SubAgentSceneState.Status.SUCCEEDED, text.take(2000))
                AgentStats.addSubAgentRun(runtimeCore.cumulativePromptTokens, runtimeCore.cumulativeCompletionTokens)
                result(agentName, true, text, "succeeded", workerRunId)
            }
        } catch (e: TimeoutCancellationException) {
            core?.stop()
            val message = "执行超时(${TASK_TIMEOUT_MS / 60_000}分钟)"
            scene?.fail(workerRunId, SubAgentSceneState.Status.TIMED_OUT, message)
            recordEvent(runId, workerRunId, agentName, "timed_out", SubAgentSceneState.Status.TIMED_OUT, message)
            result(agentName, false, message, "timed_out", workerRunId)
        } catch (e: CancellationException) {
            core?.stop()
            scene?.fail(workerRunId, SubAgentSceneState.Status.CANCELLED, "任务已停止")
            withContext(NonCancellable) {
                recordEvent(runId, workerRunId, agentName, "cancelled", SubAgentSceneState.Status.CANCELLED, "任务已停止")
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sub-agent $agentName failed: ${e.message}")
            val message = "错误: ${e.message?.take(200) ?: "未知错误"}"
            scene?.fail(workerRunId, SubAgentSceneState.Status.FAILED, message)
            recordEvent(runId, workerRunId, agentName, "failed", SubAgentSceneState.Status.FAILED, message)
            result(agentName, false, message, "failed", workerRunId)
        } finally {
            activeCores.remove(workerRunId)
        }
    }

    /** 用子智能体类型的专属工具集 + 角色系统提示,构造隔离 AgentCore。 */
    private fun buildCore(def: SubAgentEntity, workerRunId: String, runId: String): AgentCore {
        val reg = ToolRegistry()
        val toolNames = def.toolNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { READONLY_DEFAULT }
        for (n in toolNames) mainRegistry.get(n)?.let { reg.register(it) }
        // 有专属技能才赋予技能调用入口。除此之外严格遵守用户配置的白名单;
        // 不能在 UI 标注“无网络权限”却在这里静默塞入 web_search/web_fetch。
        if (def.skillNames.isNotBlank() && reg.get(INVOKE_SKILL) == null) {
            mainRegistry.get(INVOKE_SKILL)?.let { reg.register(it) }
        }
        return AgentCore(
            openAiClient = openAiClient,
            toolRegistry = reg,
            securityGate = securityGate,
            cursorDao = null,
            sessionId = -20L,
            systemPrompt = buildSystemPrompt(def)
        ).also {
            it.temperature = def.temperature.coerceIn(0f, 2f)
            it.isReviewFork = true // 不递归触发后台复盘
            it.setConfirmHandler { cmd, preview ->
                val room = scene ?: return@setConfirmHandler ToolConfirmResult.DENY
                recordEvent(
                    runId,
                    workerRunId,
                    def.name,
                    "approval_requested",
                    SubAgentSceneState.Status.WAITING_PERMISSION,
                    "${cmd.toolName}: ${preview.take(500)}"
                )
                val result = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                    room.awaitApproval(
                        workerRunId = workerRunId,
                        toolName = cmd.toolName,
                        preview = preview,
                        isIrreversible = cmd.reversibility == Reversibility.IRREVERSIBLE
                    )
                } ?: ToolConfirmResult.DENY
                recordEvent(
                    runId,
                    workerRunId,
                    def.name,
                    "approval_resolved",
                    SubAgentSceneState.Status.RUNNING,
                    "${cmd.toolName}: ${result.name.lowercase()}"
                )
                result
            }
        }
    }

    private fun cancelWorker(workerRunId: String) {
        val worker = scene?.worker(workerRunId) ?: return
        if (!worker.status.isActive) return
        cancelledWorkers.add(workerRunId)
        scene.fail(workerRunId, SubAgentSceneState.Status.CANCELLED, "正在停止")
        activeCores[workerRunId]?.stop()
        controlScope.launch {
            recordEvent(worker.runId, workerRunId, worker.agent, "cancel_requested", SubAgentSceneState.Status.CANCELLED, "用户停止单项任务")
        }
    }

    private fun stopAll() {
        val workers = scene?.snapshot?.value?.workers.orEmpty().filter { it.status.isActive }
        workers.forEach { worker ->
            cancelledWorkers.add(worker.workerRunId)
            scene?.fail(worker.workerRunId, SubAgentSceneState.Status.CANCELLED, "正在停止")
            activeCores[worker.workerRunId]?.stop()
        }
    }

    private fun retryWorker(workerRunId: String) {
        val worker = scene?.worker(workerRunId) ?: return
        if (!worker.status.isTerminal) return
        val retried = scene.prepareRetry(workerRunId) ?: return
        cancelledWorkers.remove(workerRunId)
        controlScope.launch {
            runCatching { database.commandRoomHistoryDao().reopenRun(retried.runId) }
            recordEvent(retried.runId, workerRunId, retried.agent, "retry", SubAgentSceneState.Status.QUEUED, "第 ${retried.attempt} 次执行")
            val result = workerSemaphore.withPermit {
                runOne(retried.runId, workerRunId, retried.agent, retried.task)
            }
            val outcome = if (result.optBoolean("success")) "completed" else result.optString("outcome", "failed")
            finishHistoryRun(retried.runId, outcome)
            scene.finishRun()
        }
    }

    private suspend fun recordRun(run: CommandRoomRunEntity) {
        runCatching { database.commandRoomHistoryDao().insertRun(run) }
            .onFailure { Log.w(TAG, "command room run history failed: ${it.message}") }
    }

    private suspend fun recordEvent(
        runId: String,
        workerRunId: String,
        agent: String,
        type: String,
        status: SubAgentSceneState.Status,
        content: String
    ) {
        runCatching {
            database.commandRoomHistoryDao().insertEvent(
                CommandRoomEventEntity(
                    runId = runId,
                    workerRunId = workerRunId,
                    agent = agent,
                    type = type,
                    status = status.name,
                    content = content
                )
            )
        }.onFailure { Log.w(TAG, "command room event history failed: ${it.message}") }
    }

    private suspend fun finishHistoryRun(runId: String, outcome: String) {
        runCatching {
            val dao = database.commandRoomHistoryDao()
            dao.finishRun(runId, outcome)
            dao.deleteEventsOutsideRecentRuns()
            dao.deleteRunsOutsideRecentRuns()
        }.onFailure { Log.w(TAG, "command room history cleanup failed: ${it.message}") }
    }

    private fun buildSystemPrompt(def: SubAgentEntity): String = buildString {
        append(def.systemPrompt.ifBlank { "你是一个专职子智能体:${def.name}。${def.description}" })
        val skills = def.skillNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (skills.isNotEmpty()) {
            append("\n\n你的【专属技能】(据当前任务从这几个里挑合适的,用 invoke_skill(name) 拉取其指令再照做;")
            append("不要盲目全调,也不要用清单外的):\n")
            for (s in skills) append("- ").append(s).append("\n")
        }
        append("\n完成后用简洁中文直接汇报你的结论/产出,不复述工具细节。")
    }

    private fun buildTaskPrompt(task: String): String =
        "你被主脑指派了一个子任务。\n子任务:$task\n\n请用你的专属技能与工具完成,完成后简洁汇报结论。"

    private fun result(
        agent: String,
        ok: Boolean,
        text: String,
        outcome: String,
        workerRunId: String
    ) = JSONObject().apply {
        put("worker_run_id", workerRunId)
        put("agent", agent)
        put("success", ok)
        put("outcome", outcome)
        put("result", text)
    }
}
