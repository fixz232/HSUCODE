/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: tests task-cursor iteration persistence and resume behavior.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCursorStateTest {
    @Test
    fun persistsIterationForEveryBusyState() {
        assertEquals(4, AgentCore.cursorIteration(AgentState.Thinking(4)))
        assertEquals(5, AgentCore.cursorIteration(AgentState.CallingTool(5, "write", "{}")))
        assertEquals(6, AgentCore.cursorIteration(AgentState.WaitingConfirm(6, "shell", "cmd")))
        assertEquals(7, AgentCore.cursorIteration(AgentState.Executing(7, "shell")))
    }

    @Test
    fun thinkingResumesSameIterationAndToolResultAdvances() {
        assertEquals(3, AgentCore.resumeBaseIteration("Thinking", 4))
        assertEquals(4, AgentCore.resumeBaseIteration("Executing", 4))
        assertEquals(0, AgentCore.resumeBaseIteration("Thinking", 0))
    }
}
