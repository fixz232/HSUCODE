package com.hsucode.app

import android.util.Log
import com.hsucode.data.AppDatabase
import com.hsucode.provider.JudgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Goal/Work 模式的单任务循环控制器。
 *
 * 驱动【某个 Goal 会话】自带的 [AgentChatState](池中那一份实例,与普通对话一样持久化+可后台跑):
 *   1. 把目标作为首条消息发出 → 智能体用全部工具去做
 *   2. 等这一轮跑完(isStreaming 落下)→ 取最后一条 assistant 输出
 *   3. 独立裁判(另一个模型)评估是否达成
 *   4. 未达成 → 注入裁判反馈作为下一条消息,继续下一轮;达成/超轮/超时 → 结束
 *
 * 与「主脑+子智能体」的区别:那是横向拆分并行;这里是纵向反复迭代 + 独立验收,直到确定完成。
 * 控制器跑在应用级 [appScope] 上,与前台无关 —— 切走/回来都在后台继续。
 */
class GoalLoopController(
    private val sessionId: Long,
    private val chat: AgentChatState,
    private val database: AppDatabase,
    private val taskRuntime: TaskRuntimeManager? = null,
    private val judgeFactory: () -> JudgeService,
    private val appScope: CoroutineScope,
    private val maxRounds: Int = 8,
    private val perRoundTimeoutMs: Long = 20 * 60 * 1000L,
    /** 状态回调(sessionId, status 文本):UI 横幅即时反映。 */
    private val onStatus: (Long, String) -> Unit,
    /** 完成回调(sessionId, achieved, 摘要):用于发系统通知。 */
    private val onDone: (Long, Boolean, String) -> Unit
) {
    companion object {
        private const val TAG = "GoalLoop"
        private const val RECOVERY_PREFIX = "goal_recovery_"

        data class Recovery(val sessionId: Long, val goal: String, val round: Int, val phase: String)

        suspend fun loadRecoveries(database: AppDatabase): List<Recovery> =
            database.settingDao().getByPrefix(RECOVERY_PREFIX).mapNotNull { entry ->
                runCatching {
                    val json = JSONObject(entry.value)
                    Recovery(
                        sessionId = entry.key.removePrefix(RECOVERY_PREFIX).toLong(),
                        goal = json.getString("goal"),
                        round = json.optInt("round", 0),
                        phase = json.optString("phase", "executing")
                    )
                }.getOrNull()
            }
    }

    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun start(goal: String) {
        if (isRunning) return
        job = appScope.launch { runLoop(goal, initialRound = 0, resumePhase = null) }
    }

    fun resume(recovery: Recovery) {
        if (isRunning) return
        job = appScope.launch {
            // AgentChatState cursor restoration is scheduled first during Application startup.
            // Give it one main-loop turn to become streaming before waiting for the recovered round.
            kotlinx.coroutines.delay(500)
            runLoop(recovery.goal, recovery.round, recovery.phase)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        appScope.launch {
            clearRecovery()
            setStatus("failed", "已手动停止")
        }
    }

    private suspend fun runLoop(goal: String, initialRound: Int, resumePhase: String?) {
        val judge = judgeFactory()
        setStatus("running", if (initialRound > 0) "恢复第 $initialRound 轮…" else "第 1 轮:执行中…")
        var round = initialRound
        try {
            var continueWithJudge = initialRound > 0 && resumePhase in setOf("executing", "judging")
            if (continueWithJudge && chat.isStreaming.value && !waitUntil(perRoundTimeoutMs) { !chat.isStreaming.value }) {
                throw GoalRoundTimeout("恢复的上一轮在限定时间内未结束")
            }
            while (round < maxRounds || continueWithJudge) {
                if (!continueWithJudge) {
                    round++
                    onStatus(sessionId, "第 $round 轮:执行中…")
                    persistRecovery(goal, round, "executing")

                    // 组织本轮消息:首轮=目标;之后=目标+裁判反馈。
                    val prompt = if (round == 1) firstPrompt(goal) else nextPrompt(goal, round)
                    runOneTurn(prompt)
                }
                continueWithJudge = false

                onStatus(sessionId, "第 $round 轮:裁判评估中…")
                persistRecovery(goal, round, "judging")
                val output = lastAssistantOutput()
                val verdict = judge.judgePanel(goal, output, round, voters = 3)

                if (verdict == null) {
                    Log.w(TAG, "judge failed round=$round, continue")
                    lastJudgeExplanation = "裁判 API 调用失败,继续尝试。"
                    continue
                }
                lastJudgeExplanation = verdict.explanation
                if (verdict.achieved) {
                    clearRecovery()
                    setStatus("achieved", "✓ 已达成(第 $round 轮)")
                    onDone(sessionId, true, verdict.explanation.take(140))
                    return
                }
            }
            clearRecovery()
            setStatus("failed", "✗ 达最大轮数($maxRounds)未达成")
            onDone(sessionId, false, lastJudgeExplanation.ifBlank { "达最大轮数未达成" }.take(140))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GoalRoundTimeout) {
            // Keep the cursor so a user can explicitly resume instead of judging stale output as success.
            persistRecovery(goal, round, "timed_out")
            setStatus("failed", "✗ 第 $round 轮超时，可在任务恢复中继续")
            onDone(sessionId, false, e.message.orEmpty().take(120))
        } catch (e: Exception) {
            Log.e(TAG, "goal loop error: ${e.message}", e)
            clearRecovery()
            setStatus("failed", "✗ 出错:${e.message?.take(60)}")
            onDone(sessionId, false, "出错:${e.message?.take(120)}")
        } finally {
            job = null
        }
    }

    private var lastJudgeExplanation: String = ""

    private suspend fun persistRecovery(goal: String, round: Int, phase: String) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("goal", goal)
                .put("round", round)
                .put("phase", phase)
                .put("updatedAt", System.currentTimeMillis())
            database.settingDao().put("$RECOVERY_PREFIX$sessionId", json.toString())
        }
    }

    private suspend fun clearRecovery() {
        withContext(Dispatchers.IO) {
            database.settingDao().deleteByPrefix("$RECOVERY_PREFIX$sessionId")
        }
    }

    /** 发一条消息并等这一轮完全跑完(含所有工具迭代)。 */
    private suspend fun runOneTurn(prompt: String) {
        // 等上一轮彻底空闲(保险)。
        if (!waitUntil(15_000) { !chat.isStreaming.value }) {
            throw GoalRoundTimeout("上一轮仍在执行，未能安全开始下一轮")
        }
        chat.input.value = prompt
        chat.send()
        // 等本轮开始(send 之后 isStreaming 置真有极短延迟)。
        if (!waitUntil(8_000) { chat.isStreaming.value }) {
            throw GoalRoundTimeout("本轮未能启动，请检查模型连接或权限审批")
        }
        // 等本轮结束。
        if (!waitUntil(perRoundTimeoutMs) { !chat.isStreaming.value }) {
            throw GoalRoundTimeout("本轮执行超时")
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(500)
        }
        return cond()
    }

    private class GoalRoundTimeout(message: String) : IllegalStateException(message)

    private fun lastAssistantOutput(): String =
        chat.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content?.take(4000)
            ?: "(无输出)"

    private suspend fun setStatus(status: String, text: String) {
        onStatus(sessionId, text)
        taskRuntime?.updateExternal(
            ownerKey = "goal:$sessionId",
            type = "goal",
            title = "目标任务 #$sessionId",
            status = when (status) { "running" -> TaskRunStatus.RUNNING; "achieved" -> TaskRunStatus.SUCCEEDED; else -> TaskRunStatus.FAILED },
            detail = text,
            progress = if (status == "achieved") 100 else 0
        )
        withContext(Dispatchers.IO) {
            try { database.sessionDao().setGoalStatus(sessionId, status) } catch (_: Exception) {}
        }
    }

    private fun firstPrompt(goal: String): String = buildString {
        appendLine("【目标任务】你正在自主执行一个目标,请调用你所有可用的工具持续推进,直到真正完成。")
        appendLine()
        appendLine("目标:$goal")
        appendLine()
        appendLine("完成后请明确说明:你做了什么、最终结果是什么、目标是否已达成。若还没完成就继续做。")
    }

    private fun nextPrompt(goal: String, round: Int): String = buildString {
        appendLine("【继续目标任务(第 $round 轮)】独立裁判认为目标尚未达成。")
        appendLine("目标:$goal")
        appendLine("裁判反馈:${lastJudgeExplanation.ifBlank { "未达成,请继续完善。" }}")
        appendLine()
        appendLine("请针对反馈继续推进,直到达成目标。完成后明确说明结果。")
    }
}
