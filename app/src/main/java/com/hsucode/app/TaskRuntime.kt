package com.hsucode.app

import com.hsucode.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A durable, small task journal shared by chat, goals, cron and workspace actions. */
enum class TaskRunStatus { QUEUED, RUNNING, WAITING_PERMISSION, PAUSED, SUCCEEDED, FAILED, CANCELLED }

data class TaskRunSnapshot(
    val id: Long,
    val type: String,
    val title: String,
    val status: TaskRunStatus,
    val progress: Int = 0,
    val checkpoint: String = "",
    val error: String = "",
    val ownerKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("type", type); put("title", title); put("status", status.name)
        put("progress", progress); put("checkpoint", checkpoint); put("error", error)
        put("ownerKey", ownerKey); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): TaskRunSnapshot = TaskRunSnapshot(
            id = obj.optLong("id"), type = obj.optString("type"), title = obj.optString("title"),
            status = runCatching { TaskRunStatus.valueOf(obj.optString("status")) }.getOrDefault(TaskRunStatus.FAILED),
            progress = obj.optInt("progress").coerceIn(0, 100), checkpoint = obj.optString("checkpoint"),
            error = obj.optString("error"), ownerKey = obj.optString("ownerKey"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}

/**
 * Application-scoped task registry. It deliberately stores only a journal and
 * checkpoints; the actual worker remains owned by the feature that started it.
 */
class TaskRuntimeManager(
    private val database: AppDatabase,
    private val appScope: CoroutineScope
) {
    private companion object { const val PREFIX = "task_runtime_v1_"; const val INDEX = "${PREFIX}index" }
    private val ids = AtomicLong(System.currentTimeMillis())
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _tasks = MutableStateFlow<List<TaskRunSnapshot>>(emptyList())
    val tasks: StateFlow<List<TaskRunSnapshot>> = _tasks.asStateFlow()

    init { refresh() }

    fun refresh() {
        appScope.launch(Dispatchers.IO) {
            val loaded = database.settingDao().getByPrefix(PREFIX)
                .filter { it.key != INDEX }
                .mapNotNull { runCatching { TaskRunSnapshot.fromJson(JSONObject(it.value)) }.getOrNull() }
                .sortedByDescending { it.updatedAt }
            _tasks.value = loaded
        }
    }

    fun start(type: String, title: String, checkpoint: String = "", block: suspend TaskHandle.() -> Unit): Long {
        val id = ids.incrementAndGet()
        val initial = TaskRunSnapshot(id, type, title, TaskRunStatus.QUEUED, checkpoint = checkpoint)
        persist(initial)
        jobs[id] = appScope.launch(SupervisorJob()) {
            update(id, TaskRunStatus.RUNNING)
            try {
                TaskHandle(id, this@TaskRuntimeManager).block()
                if (jobs[id]?.isActive != false) update(id, TaskRunStatus.SUCCEEDED, progress = 100)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                update(id, TaskRunStatus.CANCELLED, error = "已取消")
                throw cancel
            } catch (error: Exception) {
                update(id, TaskRunStatus.FAILED, error = error.message.orEmpty())
            } finally { jobs.remove(id) }
        }
        return id
    }

    /** Attach a durable status to an existing feature-owned worker (goal/cron/chat). */
    fun updateExternal(ownerKey: String, type: String, title: String, status: TaskRunStatus, detail: String = "", progress: Int = 0) {
        val id = -kotlin.math.abs(ownerKey.hashCode().toLong()).coerceAtLeast(1L)
        appScope.launch(Dispatchers.IO) {
            val existing = _tasks.value.firstOrNull { it.id == id }
            persist(TaskRunSnapshot(id, type, title, status, progress.coerceIn(0, 100), detail,
                error = if (status == TaskRunStatus.FAILED) detail else "", ownerKey = ownerKey,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()))
        }
    }

    fun cancel(id: Long) { jobs.remove(id)?.cancel(); appScope.launch(Dispatchers.IO) { update(id, TaskRunStatus.CANCELLED, error = "已取消") } }

    fun delete(id: Long) {
        jobs.remove(id)?.cancel()
        appScope.launch(Dispatchers.IO) {
            database.settingDao().deleteByPrefix("$PREFIX$id")
            _tasks.value = _tasks.value.filterNot { it.id == id }
        }
    }

    internal suspend fun update(id: Long, status: TaskRunStatus, progress: Int = -1, checkpoint: String? = null, error: String? = null) {
        withContext(Dispatchers.IO) {
            val old = _tasks.value.firstOrNull { it.id == id } ?: return@withContext
            persist(old.copy(status = status, progress = if (progress >= 0) progress.coerceIn(0, 100) else old.progress,
                checkpoint = checkpoint ?: old.checkpoint, error = error ?: old.error, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun persist(snapshot: TaskRunSnapshot) {
        // Publish before scheduling the Room write so a just-started worker can
        // immediately advance its own state without racing the initial insert.
        _tasks.value = (_tasks.value.filterNot { it.id == snapshot.id } + snapshot).sortedByDescending { it.updatedAt }
        appScope.launch(Dispatchers.IO) {
            database.settingDao().put("$PREFIX${snapshot.id}", snapshot.toJson().toString())
        }
    }

    class TaskHandle internal constructor(private val id: Long, private val manager: TaskRuntimeManager) {
        suspend fun checkpoint(value: String, progress: Int = -1) = manager.update(id, TaskRunStatus.RUNNING, progress, value)
        suspend fun waitingPermission(detail: String) = manager.update(id, TaskRunStatus.WAITING_PERMISSION, checkpoint = detail)
        fun isActive(): Boolean = manager.jobs[id]?.isActive == true
    }
}
