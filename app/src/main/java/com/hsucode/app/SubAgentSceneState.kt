package com.hsucode.app

import com.hsucode.security.ToolConfirmResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Thread-safe runtime model for the agent command room. */
class SubAgentSceneState(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    enum class Status {
        QUEUED,
        PREPARING,
        RUNNING,
        WAITING_PERMISSION,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        UNKNOWN;

        val isActive: Boolean
            get() = this == QUEUED || this == PREPARING || this == RUNNING || this == WAITING_PERMISSION
        val isTerminal: Boolean get() = !isActive
    }

    data class Worker(
        val workerRunId: String,
        val runId: String,
        val agent: String,
        val task: String,
        val status: Status = Status.QUEUED,
        val currentTool: String = "",
        val activity: String = "",
        val result: String = "",
        val error: String = "",
        val attempt: Int = 1,
        val createdAt: Long,
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        val updatedAt: Long = createdAt
    )

    data class ApprovalRequest(
        val requestId: String,
        val workerRunId: String,
        val agent: String,
        val toolName: String,
        val preview: String,
        val isIrreversible: Boolean,
        val createdAt: Long
    )

    data class Snapshot(
        val runId: String = "",
        val workers: List<Worker> = emptyList(),
        val approvals: List<ApprovalRequest> = emptyList(),
        val visible: Boolean = false,
        val startedAt: Long = 0,
        val endedAt: Long = 0
    ) {
        val brainBusy: Boolean get() = workers.any { it.status.isActive }
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val approvalDeferreds = ConcurrentHashMap<String, CompletableDeferred<ToolConfirmResult>>()

    @Volatile private var cancelWorkerAction: (String) -> Unit = {}
    @Volatile private var retryWorkerAction: (String) -> Unit = {}
    @Volatile private var stopAllAction: () -> Unit = {}

    fun bindActions(
        onCancelWorker: (String) -> Unit,
        onRetryWorker: (String) -> Unit,
        onStopAll: () -> Unit
    ) {
        cancelWorkerAction = onCancelWorker
        retryWorkerAction = onRetryWorker
        stopAllAction = onStopAll
    }

    /** Starts one dispatch run and returns stable workers in assignment order. */
    fun begin(items: List<Pair<String, String>>): List<Worker> {
        val now = clock()
        val runId = idFactory()
        val workers = items.mapIndexed { index, (agent, task) ->
            Worker(
                workerRunId = "$runId:$index",
                runId = runId,
                agent = agent,
                task = task,
                createdAt = now,
                updatedAt = now
            )
        }
        approvalDeferreds.values.forEach { it.complete(ToolConfirmResult.DENY) }
        approvalDeferreds.clear()
        _snapshot.value = Snapshot(
            runId = runId,
            workers = workers,
            visible = workers.isNotEmpty(),
            startedAt = if (workers.isEmpty()) 0 else now
        )
        return workers
    }

    fun worker(workerRunId: String): Worker? =
        _snapshot.value.workers.firstOrNull { it.workerRunId == workerRunId }

    fun setStatus(workerRunId: String, status: Status, message: String = "") {
        updateWorker(workerRunId) { worker ->
            val now = clock()
            worker.copy(
                status = status,
                activity = message.ifBlank { worker.activity },
                startedAt = if (status == Status.RUNNING && worker.startedAt == 0L) now else worker.startedAt,
                finishedAt = if (status.isTerminal) now else 0,
                updatedAt = now
            )
        }
    }

    fun setTool(workerRunId: String, toolName: String, activity: String = "") {
        updateWorker(workerRunId) {
            it.copy(currentTool = toolName, activity = activity.ifBlank { it.activity }, updatedAt = clock())
        }
    }

    fun setActivity(workerRunId: String, activity: String) {
        updateWorker(workerRunId) { it.copy(activity = activity, updatedAt = clock()) }
    }

    fun complete(workerRunId: String, result: String) {
        updateWorker(workerRunId) {
            val now = clock()
            it.copy(
                status = Status.SUCCEEDED,
                result = result,
                error = "",
                currentTool = "",
                finishedAt = now,
                updatedAt = now
            )
        }
    }

    fun fail(workerRunId: String, status: Status = Status.FAILED, error: String) {
        require(status.isTerminal) { "Failure status must be terminal" }
        updateWorker(workerRunId) {
            val now = clock()
            it.copy(
                status = status,
                error = error,
                currentTool = "",
                finishedAt = now,
                updatedAt = now
            )
        }
    }

    fun prepareRetry(workerRunId: String): Worker? {
        var retried: Worker? = null
        updateWorker(workerRunId) {
            val now = clock()
            it.copy(
                status = Status.QUEUED,
                currentTool = "",
                activity = "等待重新执行",
                result = "",
                error = "",
                attempt = it.attempt + 1,
                startedAt = 0,
                finishedAt = 0,
                updatedAt = now
            ).also { worker -> retried = worker }
        }
        return retried
    }

    fun finishRun() {
        _snapshot.update { state -> state.copy(endedAt = clock()) }
    }

    fun requestCancel(workerRunId: String) {
        denyApprovalsForWorker(workerRunId)
        cancelWorkerAction(workerRunId)
    }

    fun requestRetry(workerRunId: String) = retryWorkerAction(workerRunId)

    fun requestStopAll() {
        _snapshot.value.approvals.forEach { resolveApproval(it.requestId, ToolConfirmResult.DENY) }
        stopAllAction()
    }

    suspend fun awaitApproval(
        workerRunId: String,
        toolName: String,
        preview: String,
        isIrreversible: Boolean
    ): ToolConfirmResult {
        val worker = worker(workerRunId) ?: return ToolConfirmResult.DENY
        val request = ApprovalRequest(
            requestId = idFactory(),
            workerRunId = workerRunId,
            agent = worker.agent,
            toolName = toolName,
            preview = preview,
            isIrreversible = isIrreversible,
            createdAt = clock()
        )
        val deferred = CompletableDeferred<ToolConfirmResult>()
        approvalDeferreds[request.requestId] = deferred
        _snapshot.update { state ->
            state.copy(
                workers = state.workers.map {
                    if (it.workerRunId == workerRunId) it.copy(
                        status = Status.WAITING_PERMISSION,
                        currentTool = toolName,
                        activity = "等待权限审批",
                        updatedAt = clock()
                    ) else it
                },
                approvals = state.approvals + request
            )
        }
        return try {
            deferred.await()
        } finally {
            approvalDeferreds.remove(request.requestId)
            _snapshot.update { state ->
                state.copy(
                    approvals = state.approvals.filterNot { it.requestId == request.requestId },
                    workers = state.workers.map {
                        if (it.workerRunId == workerRunId && it.status == Status.WAITING_PERMISSION) {
                            it.copy(status = Status.RUNNING, activity = "权限审批已处理", updatedAt = clock())
                        } else it
                    }
                )
            }
        }
    }

    fun resolveApproval(requestId: String, result: ToolConfirmResult): Boolean =
        approvalDeferreds[requestId]?.complete(result) == true

    fun close() {
        approvalDeferreds.values.forEach { it.complete(ToolConfirmResult.DENY) }
        approvalDeferreds.clear()
        _snapshot.value = Snapshot()
    }

    private fun denyApprovalsForWorker(workerRunId: String) {
        _snapshot.value.approvals
            .filter { it.workerRunId == workerRunId }
            .forEach { resolveApproval(it.requestId, ToolConfirmResult.DENY) }
    }

    private inline fun updateWorker(workerRunId: String, transform: (Worker) -> Worker) {
        _snapshot.update { state ->
            state.copy(workers = state.workers.map {
                if (it.workerRunId == workerRunId) transform(it) else it
            })
        }
    }
}
