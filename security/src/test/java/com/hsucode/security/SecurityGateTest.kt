package com.hsucode.security

import com.hsucode.data.PermissionRuleEntity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * gap-12/13/14/15 安全闸门决策单测(纯逻辑,可 FAIL)。
 */
class SecurityGateTest {

    private fun gate() = SecurityGateImpl(null)
    private fun shell(cmd: String) = "{\"command\":\"$cmd\"}"

    private fun decide(g: SecurityGateImpl, tool: String, args: String, mode: PermissionMode): Decision {
        val c = g.classify(tool, args)
        return g.decide(c, mode)
    }

    // 允许全部(全自动):真·放行一切——危险命令也直接放行(仅致命 FATAL_BANNED 与显式 deny 规则能挡)。
    @Test fun allowAll_dangerous_allows() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("rm -rf /data/data/x"), PermissionMode.ALLOW_ALL) is Decision.Allow)
    }

    @Test fun allowAll_normal_allows() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("echo hi"), PermissionMode.ALLOW_ALL) is Decision.Allow)
    }

    @Test fun fatal_alwaysDenied() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("rm -rf /"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // gap-13:ASK 下只读安全命令自动放行,非安全命令需确认。
    @Test fun ask_safeCommand_allows() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("ls -la"), PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "shell_exec", shell("git status"), PermissionMode.ASK) is Decision.Allow)
    }

    @Test fun ask_chainWithUnsafe_needsConfirm() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("ls && rm foo"), PermissionMode.ASK) is Decision.NeedConfirm)
    }

    @Test fun ask_shellEvaluation_needsConfirm() {
        val g = gate()
        for (command in listOf(
            "cat $(touch /tmp/pwned)",
            "env sh -c 'touch /tmp/pwned'",
            "find . -exec touch /tmp/pwned ;",
            "echo `id`"
        )) {
            assertTrue(command, decide(g, "shell_exec", shell(command), PermissionMode.ASK) is Decision.NeedConfirm)
        }
    }

    @Test fun audit_redactsSecrets() {
        val g = gate()
        val cmd = g.classify("shell_exec", "{\"command\":\"curl -H 'Authorization: Bearer top-secret'\",\"apiKey\":\"abc123\"}")
        g.audit(cmd, Decision.Allow("test"), "access_token=def456")
        val entry = g.getAuditTrail().single()
        assertFalse(entry.toolArgs.contains("top-secret"))
        assertFalse(entry.toolArgs.contains("abc123"))
        assertFalse(entry.result.orEmpty().contains("def456"))
    }

    @Test fun ask_readOnlyTool_allows() {
        val g = gate()
        assertTrue(decide(g, "file_read", "{\"path\":\"a.txt\"}", PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "grep", "{\"pattern\":\"x\"}", PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "web_search", "{\"query\":\"kotlin\"}", PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "web_fetch", "{\"url\":\"https://example.com\"}", PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "invoke_skill", "{\"name\":\"review\"}", PermissionMode.ASK) is Decision.Allow)
    }

    // gap-15:只读模式放行只读、拒绝写。
    @Test fun readOnly_denies_write_allows_read() {
        val g = gate()
        assertTrue(decide(g, "file_write", "{\"path\":\"a\",\"content\":\"b\"}", PermissionMode.READ_ONLY) is Decision.Denied)
        assertTrue(decide(g, "file_read", "{\"path\":\"a\"}", PermissionMode.READ_ONLY) is Decision.Allow)
    }

    // gap-12:deny 规则即使 ALLOW_ALL 也拒;allow 规则跳过确认;deny > allow。
    @Test fun rule_deny_overrides_allowAll() {
        val g = gate()
        g.setPermissionRules(listOf(PermissionRuleEntity(action = "deny", toolFilter = "shell_exec", pattern = "*rm*", createdAt = 0)))
        assertTrue(decide(g, "shell_exec", shell("rm foo"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    @Test fun rule_allow_skips_confirm_in_ask() {
        val g = gate()
        g.setPermissionRules(listOf(PermissionRuleEntity(action = "allow", toolFilter = "shell_exec", pattern = "*npm*", createdAt = 0)))
        assertTrue(decide(g, "shell_exec", shell("npm install"), PermissionMode.ASK) is Decision.Allow)
    }
}
