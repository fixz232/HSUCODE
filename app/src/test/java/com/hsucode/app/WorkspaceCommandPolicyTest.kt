package com.hsucode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCommandPolicyTest {
    @Test
    fun packageAndDownloadCommandsGetLongTimeout() {
        assertEquals(WorkspaceCommandPolicy.LONG_TIMEOUT_SECONDS, WorkspaceCommandPolicy.timeoutSeconds("apt-get update -y && apt-get install nodejs"))
        assertEquals(WorkspaceCommandPolicy.LONG_TIMEOUT_SECONDS, WorkspaceCommandPolicy.timeoutSeconds("git clone https://example.com/repo.git"))
        assertTrue(WorkspaceCommandPolicy.isLongTask("npm install"))
    }

    @Test
    fun normalCommandsStayResponsive() {
        assertEquals(WorkspaceCommandPolicy.DEFAULT_TIMEOUT_SECONDS, WorkspaceCommandPolicy.timeoutSeconds("printf hello"))
    }
}
