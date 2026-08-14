package com.hsucode.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Owns MCP transports and retries a dropped HTTP/SSE or stdio session with bounded backoff. */
class McpSessionRegistry {
    enum class Status { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }
    data class SessionState(val key: String, val status: Status, val attempts: Int = 0, val message: String = "")

    private val sessions = ConcurrentHashMap<String, ResilientTransport>()
    private val stateStore = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val states: StateFlow<Map<String, SessionState>> = stateStore.asStateFlow()

    fun acquire(key: String, factory: () -> McpTransport): McpTransport =
        sessions.getOrPut(key) {
            ResilientTransport(key, factory) { state -> publish(state) }
        }

    suspend fun reconnect(key: String): Result<Unit> = sessions[key]?.reconnect() ?: Result.failure(IllegalStateException("MCP 会话不存在"))

    fun close(key: String) {
        sessions.remove(key)?.close()
        publish(SessionState(key, Status.DISCONNECTED))
    }

    fun closeAll() = sessions.keys.toList().forEach(::close)

    private fun publish(state: SessionState) {
        stateStore.value = stateStore.value.toMutableMap().apply { put(state.key, state) }
    }

    private class ResilientTransport(
        private val key: String,
        private val factory: () -> McpTransport,
        private val onState: (SessionState) -> Unit
    ) : McpTransport {
        @Volatile private var delegate: McpTransport? = null
        @Volatile private var attempts = 0
        @Volatile private var serverInfo: McpServerInfo? = null
        private val connectLock = Mutex()

        private suspend fun current(): McpTransport = connectLock.withLock {
            delegate ?: factory().also { delegate = it }
        }

        override suspend fun initialize(): McpServerInfo {
            onState(SessionState(key, Status.CONNECTING, attempts))
            return try {
                val info = current().initialize()
                serverInfo = info
                attempts = 0
                onState(SessionState(key, Status.CONNECTED, attempts))
                info
            } catch (error: Exception) {
                // Drop a half-initialized transport; a later reconnect must create a fresh process/socket.
                delegate?.close()
                delegate = null
                reconnect().getOrThrow()
                serverInfo ?: throw error
            }
        }

        override suspend fun listTools(): List<McpToolInfo> = retry { it.listTools() }

        override suspend fun callTool(toolName: String, arguments: org.json.JSONObject): String =
            retry { it.callTool(toolName, arguments) }

        private suspend fun <T> retry(operation: suspend (McpTransport) -> T): T {
            try { return operation(current()) } catch (first: Exception) {
                reconnect().getOrThrow()
                return operation(current())
            }
        }

        suspend fun reconnect(): Result<Unit> {
            var lastError: Exception? = null
            return try {
                connectLock.withLock {
                    delegate?.close()
                    delegate = null
                    repeat(3) { index ->
                        attempts = index + 1
                        onState(SessionState(key, Status.RECONNECTING, attempts, lastError?.message.orEmpty()))
                        val next = try { factory() } catch (error: Exception) {
                            lastError = error
                            null
                        }
                        if (next != null) {
                            try {
                                val info = next.initialize()
                                delegate = next
                                serverInfo = info
                                attempts = 0
                                onState(SessionState(key, Status.CONNECTED, attempts))
                                return@withLock Result.success(Unit)
                            } catch (error: Exception) {
                                next.close()
                                lastError = error
                            }
                        }
                        if (index < 2) delay(250L shl index)
                    }
                    Result.failure(lastError ?: IllegalStateException("MCP 重连失败"))
                }
            } catch (error: Exception) {
                Result.failure(error)
            }.also { result ->
                result.exceptionOrNull()?.let {
                    onState(SessionState(key, Status.ERROR, attempts, it.message.orEmpty()))
                }
            }
        }

        override fun close() {
            delegate?.close()
            delegate = null
            serverInfo = null
        }
    }
}
