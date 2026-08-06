package com.hsucode.app

/** Execution limits for workspace commands. Package installation and downloads are expected to be slow. */
internal object WorkspaceCommandPolicy {
    const val DEFAULT_TIMEOUT_SECONDS = 60L
    const val LONG_TIMEOUT_SECONDS = 30L * 60L

    private val longTask = Regex(
        "(?i)\\b(apt(-get)?|apk|dnf|yum|pip(3)?|uvx?|npm|npx|pnpm|yarn|gradle|sdkmanager|git\\s+(clone|pull|fetch)|curl|wget|tar|unzip|make)\\b"
    )

    fun timeoutSeconds(command: String): Long =
        if (longTask.containsMatchIn(command)) LONG_TIMEOUT_SECONDS else DEFAULT_TIMEOUT_SECONDS

    fun isLongTask(command: String): Boolean = timeoutSeconds(command) == LONG_TIMEOUT_SECONDS
}
