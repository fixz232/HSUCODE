package com.hsucode.app

import com.hsucode.security.ToolConfirmResult
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentSceneStateTest {
    @Test
    fun duplicateAgentAssignmentsHaveStableUniqueWorkerIds() {
        val state = stateWithIds("run-a")

        val workers = state.begin(listOf("researcher" to "task one", "researcher" to "task two"))

        assertEquals(2, workers.size)
        assertNotEquals(workers[0].workerRunId, workers[1].workerRunId)
        assertEquals("run-a:0", workers[0].workerRunId)
        assertEquals("run-a:1", workers[1].workerRunId)
    }

    @Test
    fun terminalStatesAlwaysClearBusyState() {
        var now = 100L
        val state = SubAgentSceneState(clock = { now }, idFactory = { "run-b" })
        val workers = state.begin(listOf("a" to "one", "b" to "two"))
        state.setStatus(workers[0].workerRunId, SubAgentSceneState.Status.RUNNING)
        state.setStatus(workers[1].workerRunId, SubAgentSceneState.Status.RUNNING)
        assertTrue(state.snapshot.value.brainBusy)

        now = 200L
        state.complete(workers[0].workerRunId, "done")
        state.fail(workers[1].workerRunId, SubAgentSceneState.Status.CANCELLED, "cancelled")
        state.finishRun()

        assertFalse(state.snapshot.value.brainBusy)
        assertEquals(200L, state.snapshot.value.endedAt)
    }

    @Test
    fun concurrentApprovalsResolveByRequestId() = runTest {
        val ids = ArrayDeque(listOf("run-c", "approval-1", "approval-2"))
        val state = SubAgentSceneState(idFactory = { ids.removeFirst() })
        val workers = state.begin(listOf("a" to "one", "b" to "two"))

        val first = async { state.awaitApproval(workers[0].workerRunId, "shell_exec", "echo one", false) }
        val second = async { state.awaitApproval(workers[1].workerRunId, "file_write", "/tmp/two", true) }
        runCurrent()
        val requests = state.snapshot.value.approvals
        assertEquals(2, requests.size)

        assertTrue(state.resolveApproval("approval-2", ToolConfirmResult.DENY))
        assertTrue(state.resolveApproval("approval-1", ToolConfirmResult.ALLOW_ONCE))

        assertEquals(ToolConfirmResult.ALLOW_ONCE, first.await())
        assertEquals(ToolConfirmResult.DENY, second.await())
        assertTrue(state.snapshot.value.approvals.isEmpty())
    }

    @Test
    fun cancellingWorkerDeniesItsPendingApprovalOnly() = runTest {
        val ids = ArrayDeque(listOf("run-d", "approval-d"))
        val state = SubAgentSceneState(idFactory = { ids.removeFirst() })
        val worker = state.begin(listOf("a" to "one")).single()
        var cancelledId = ""
        state.bindActions(onCancelWorker = { cancelledId = it }, onRetryWorker = {}, onStopAll = {})
        val approval = async { state.awaitApproval(worker.workerRunId, "shell_exec", "rm file", true) }
        runCurrent()

        state.requestCancel(worker.workerRunId)

        assertEquals(worker.workerRunId, cancelledId)
        assertEquals(ToolConfirmResult.DENY, approval.await())
        assertTrue(state.snapshot.value.approvals.isEmpty())
    }

    @Test
    fun retryPreservesWorkerIdentityAndClearsTerminalData() {
        val state = stateWithIds("run-e")
        val worker = state.begin(listOf("a" to "one")).single()
        state.fail(worker.workerRunId, SubAgentSceneState.Status.FAILED, "network")

        val retried = state.prepareRetry(worker.workerRunId)!!

        assertEquals(worker.workerRunId, retried.workerRunId)
        assertEquals(2, retried.attempt)
        assertEquals(SubAgentSceneState.Status.QUEUED, retried.status)
        assertTrue(retried.error.isEmpty())
        assertTrue(retried.result.isEmpty())
    }

    private fun stateWithIds(vararg ids: String): SubAgentSceneState {
        val values = ArrayDeque(ids.toList())
        return SubAgentSceneState(idFactory = { values.removeFirst() })
    }
}
