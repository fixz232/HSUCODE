package com.hsucode.security

/**
 * Permission modes for the security gate.
 * Controls how tool calls are handled (allow/ask/deny/read-only/plan).
 */
enum class PermissionMode {
    /** Execute directly. Fatal operations and explicit deny rules still apply. */
    ALLOW_ALL,
    /** Ask user each time via confirmation card(gap-13: 只读安全命令自动放行). */
    ASK,
    /** Auto-approve normal operations, but request approval when risk detection marks them dangerous. */
    AUTO_APPROVE_RISK,
    /** Reject all tool calls, no confirmation card. */
    DENY_ALL,
    /** gap-15 只读模式:放行只读工具/安全命令,拒绝一切写/执行(file_write/file_edit/multi_edit/su_exec/写类 shell)。 */
    READ_ONLY,
    /** gap-15 计划模式:与只读一致的探索模式,配合 agent_plan 先规划不落盘。 */
    PLAN
}
