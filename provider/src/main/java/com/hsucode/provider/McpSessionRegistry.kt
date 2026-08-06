package com.hsucode.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Owns MCP transports and retries a dropped HTTP/SSE or stdio session once. */
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

        private suspend fun current(): McpTransport {
            delegate?.let { return it }
            val created = factory()
            delegate = created
            return created
        }

        override suspend fun initialize(): McpServerInfo {
            onState(SessionState(key, Status.CONNECTING, attempts))
            return try {
                val info = current().initialize()
                attempts = 0
                onState(SessionState(key, Status.CONNECTED, attempts))
                info
            } catch (error: Exception) {
                onState(SessionState(key, Status.ERROR, attempts, error.message.orEmpty()))
                throw error
            }
        }

        override suspend fun listTools(): List<McpToolInfo> = retry { it.listTools() }

        override suspend fun callTool(toolName: String, arguments: org.json.JSONObject): String =
            retry { it.callTool(toolName, arguments) }

        private suspend fun <T> retry(operation: suspend (McpTransport) -> T): T {
            try { return operation(current()) } catch (first: Exception) {
                onState(SessionState(key, Status.RECONNECTING, ++attempts, first.message.orEmpty()))
                reconnect().getOrThrow()
                return operation(current())
            }
        }

        suspend fun reconnect(): Result<Unit> = runCatching {
            delegate?.close()
            val next = factory()
            delegate = next
            next.initialize()
            attempts = 0
            onState(SessionState(key, Status.CONNECTED, attempts))
        }.onFailure { onState(SessionState(key, Status.ERROR, attempts, it.message.orEmpty())) }

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}
