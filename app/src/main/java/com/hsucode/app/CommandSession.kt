package com.hsucode.app

/** Common lifecycle contract for the app shell, PRoot shell and Shizuku shell. */
internal interface CommandSession : java.io.Closeable {
    suspend fun execute(command: String): Int
    suspend fun executeWithOutput(command: String): CommandResult
    fun send(bytes: ByteArray)
    fun isAlive(): Boolean

    data class CommandResult(val exitCode: Int, val output: String)
}
