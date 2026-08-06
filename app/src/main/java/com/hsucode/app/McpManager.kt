package com.hsucode.app

import android.util.Log
import com.hsucode.core.ToolRegistry
import com.hsucode.data.AppDatabase
import com.hsucode.data.McpServerEntity
import com.hsucode.provider.McpClient
import com.hsucode.provider.McpServerInfo
import com.hsucode.provider.McpSessionRegistry
import com.hsucode.provider.McpOAuthClient
import com.hsucode.security.KeystoreProvider
import com.hsucode.tools.McpToolAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Manages MCP server connections and synchronizes their tools to the ToolRegistry.
 *
 * Lifecycle:
 * - connectServer(): initialize handshake → discover tools → register adapters → update Room
 * - disconnectServer(): disconnect client → unregister adapters → update Room
 * - syncFromRoom(): on startup, restore previously connected servers
 */
class McpManager(
    private val database: AppDatabase,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val keystore: KeystoreProvider
) {
    companion object {
        private const val TAG = "McpManager"
    }

    /** Active connections: key(url 或 stdio:name)→ 传输(HTTP/stdio) */
    private val clients = mutableMapOf<String, com.hsucode.provider.McpTransport>()
    val sessionRegistry = McpSessionRegistry()

    suspend fun requestOAuthDeviceCode(endpoint: String, clientId: String, scope: String = "") =
        McpOAuthClient.requestDeviceCode(okHttpClient, endpoint, clientId, scope)

    suspend fun pollOAuthToken(endpoint: String, clientId: String, code: McpOAuthClient.DeviceCode) =
        McpOAuthClient.pollToken(okHttpClient, endpoint, clientId, code)

    /** Active tool adapters registered in ToolRegistry: server URL → tool adapter names */
    private val registeredTools = mutableMapOf<String, List<String>>()

    /** Connect to an MCP server, discover tools, and register them. */
    suspend fun connectServer(
        name: String,
        url: String,
        authHeader: String = ""
    ): McpConnectResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to MCP server: $name ($url)")

            val key = "http:$url"
            // The registry wraps the transport so a dropped HTTP/SSE session is rebuilt once.
            val client = sessionRegistry.acquire(key) { McpClient(okHttpClient, url, authHeader) }
            val serverInfo = client.initialize()

            // Discover tools
            val tools = client.listTools()
            Log.i(TAG, "Discovered ${tools.size} tools from $name")

            // Register adapters
            val adapterNames = mutableListOf<String>()
            for (toolInfo in tools) {
                val adapter = McpToolAdapter(client, toolInfo, name) // gap-21 传入 server 名做命名空间
                toolRegistry.register(adapter)
                adapterNames.add(adapter.name)
            }

            // Store connection
            clients[key] = client
            registeredTools[key] = adapterNames

            // Update Room
            val existing = database.mcpServerDao().getByUrl(url)
            val entity = McpServerEntity(
                id = existing?.id ?: 0,
                name = name,
                url = url,
                authHeader = keystore.encryptString(authHeader),
                connected = true,
                toolNames = tools.joinToString(",") { it.name },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.mcpServerDao().upsert(entity)

            McpConnectResult.Success(
                serverName = serverInfo.name,
                toolCount = tools.size,
                toolNames = tools.map { it.name }
            )
        } catch (e: Exception) {
            Log.e(TAG, "MCP connect failed: ${e.message}", e)
            runCatching {
                saveServerConfig(McpServerEntity(name = name, url = url, authHeader = authHeader, transport = "http"))
            }
            McpConnectResult.Error(e.message ?: "连接失败")
        }
    }

    /**
     * gap-22:连接【本地 stdio】MCP 服务器——ProcessBuilder 拉起 command/args/env(可选 root),
     * 管道走 JSON-RPC。设备需具备可执行运行时(Termux 的 node/npx/uvx 或 root 可执行二进制)。
     * key 用 "stdio:$name"。
     */
    suspend fun connectStdioServer(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
        runAsRoot: Boolean = false
    ): McpConnectResult = withContext(Dispatchers.IO) {
        val key = "stdio:$name"
        try {
            Log.i(TAG, "Connecting to stdio MCP server: $name ($command)")
            // Android 本身没有 Node/Python 运行时；当用户部署了免 Root Ubuntu 后，
            // 将 npx/uvx MCP 持久进程放进 PRoot，并保留真正的 stdin/stdout 管道。
            // Root 配置仍走原本显式的 su 路径，不会被静默替换。
            val prootLauncher = if (!runAsRoot && WorkspaceRuntime.backend() == WorkspaceRuntime.Backend.PROOT_UBUNTU) {
                { ProotLinuxEnvironment.startMcpProcess(command, args, env) }
            } else null
            val client = sessionRegistry.acquire(key) {
                com.hsucode.provider.McpStdioClient(command, args, env, runAsRoot, prootLauncher)
            }
            val serverInfo = client.initialize()
            val tools = client.listTools()
            val adapterNames = mutableListOf<String>()
            for (toolInfo in tools) {
                val adapter = McpToolAdapter(client, toolInfo, name)
                toolRegistry.register(adapter)
                adapterNames.add(adapter.name)
            }
            clients[key] = client
            registeredTools[key] = adapterNames

            val existing = database.mcpServerDao().getAll().firstOrNull { it.transport == "stdio" && it.name == name }
            val entity = McpServerEntity(
                id = existing?.id ?: 0,
                name = name,
                url = existing?.url ?: "",
                connected = true,
                toolNames = tools.joinToString(",") { it.name },
                transport = "stdio",
                command = command,
                argsJson = org.json.JSONArray(args).toString(),
                envJson = keystore.encryptString(
                    org.json.JSONObject().apply { env.forEach { (k, v) -> put(k, v) } }.toString()
                ),
                runAsRoot = runAsRoot,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.mcpServerDao().upsert(entity)
            McpConnectResult.Success(serverInfo.name, tools.size, tools.map { it.name })
        } catch (e: Exception) {
            Log.e(TAG, "stdio MCP connect failed: ${e.message}", e)
            clients[key]?.close(); clients.remove(key)
            runCatching {
                saveServerConfig(McpServerEntity(
                    name = name, url = "", transport = "stdio", command = command,
                    argsJson = org.json.JSONArray(args).toString(),
                    envJson = org.json.JSONObject(env).toString(), runAsRoot = runAsRoot
                ))
            }
            McpConnectResult.Error(e.message ?: "本地 MCP 连接失败(请检查 Ubuntu 中的 node/npx 或 uvx 是否已安装)")
        }
    }

    /** Connect a persisted HTTP or stdio server and decrypt credentials only in memory. */
    suspend fun connectStoredServer(serverId: Long): McpConnectResult = withContext(Dispatchers.IO) {
        val server = database.mcpServerDao().getById(serverId)
            ?: return@withContext McpConnectResult.Error("MCP 服务器不存在")
        try {
            if (server.transport == "stdio") {
                connectStdioServer(
                    server.name,
                    server.command,
                    parseJsonArray(server.argsJson),
                    parseJsonObject(keystore.decryptString(server.envJson)),
                    server.runAsRoot
                )
            } else {
                connectServer(server.name, server.url, keystore.decryptString(server.authHeader))
            }
        } catch (e: Exception) {
            McpConnectResult.Error("凭据解密失败: ${e.message ?: "未知错误"}")
        }
    }

    /** Disconnect from an MCP server and unregister its tools. */
    suspend fun disconnectServer(serverId: Long): Unit = withContext(Dispatchers.IO) {
        val entity = database.mcpServerDao().getById(serverId) ?: return@withContext
        val key = connectionKey(entity)
        Log.i(TAG, "Disconnecting from MCP server: ${entity.name}")

        // Unregister adapters
        val adapterNames = registeredTools[key] ?: emptyList()
        for (name in adapterNames) {
            toolRegistry.unregister(name)
        }
        registeredTools.remove(key)

        // Disconnect client
        sessionRegistry.close(key)
        clients[key]?.close()
        clients.remove(key)

        // Update Room
        database.mcpServerDao().update(entity.copy(connected = false, updatedAt = System.currentTimeMillis()))
    }

    /** Restore connections from previously connected servers in Room. */
    suspend fun syncFromRoom(): List<McpConnectResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<McpConnectResult>()
        val servers = database.mcpServerDao().getAll()

        for (server in servers) {
            if (!server.connected) continue
            val result = connectStoredServer(server.id)
            results.add(result)
        }

        results
    }

    private fun parseJsonArray(s: String): List<String> = try {
        val a = org.json.JSONArray(s.ifBlank { "[]" }); (0 until a.length()).map { a.optString(it) }
    } catch (_: Exception) { emptyList() }

    private fun parseJsonObject(s: String): Map<String, String> = try {
        val o = org.json.JSONObject(s.ifBlank { "{}" }); o.keys().asSequence().associateWith { o.optString(it) }
    } catch (_: Exception) { emptyMap() }

    /** Get all stored MCP server entities. */
    suspend fun getAllServers(): List<McpServerEntity> {
        val servers = database.mcpServerDao().getAll()
        for (server in servers) migrateSecrets(server)
        return database.mcpServerDao().getAll()
    }

    /** Persist a disconnected server while encrypting credential-bearing fields. */
    suspend fun saveServerConfig(server: McpServerEntity): Long = withContext(Dispatchers.IO) {
        val existing = database.mcpServerDao().getAll().firstOrNull {
            if (server.transport == "stdio") it.transport == "stdio" && it.name == server.name
            else it.transport != "stdio" && it.url == server.url
        }
        database.mcpServerDao().upsert(server.copy(
            id = existing?.id ?: server.id,
            authHeader = seal(server.authHeader),
            envJson = seal(server.envJson),
            createdAt = existing?.createdAt ?: server.createdAt,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /** Delete an MCP server entry from Room (disconnects first if connected). */
    suspend fun deleteServer(serverId: Long) {
        val entity = database.mcpServerDao().getById(serverId) ?: return
        if (clients.containsKey(connectionKey(entity))) disconnectServer(serverId)
        database.mcpServerDao().deleteById(serverId)
    }

    private suspend fun migrateSecrets(server: McpServerEntity) {
        val auth = seal(server.authHeader)
        val env = seal(server.envJson)
        if (auth != server.authHeader || env != server.envJson) {
            database.mcpServerDao().update(server.copy(authHeader = auth, envJson = env))
        }
    }

    private fun seal(value: String): String = when {
        value.isBlank() || keystore.isEncryptedString(value) -> value
        else -> keystore.encryptString(value)
    }

    private fun connectionKey(server: McpServerEntity): String =
        if (server.transport == "stdio") "stdio:${server.name}" else "http:${server.url}"

    fun activeConnectionCount(): Int = clients.size
}

sealed class McpConnectResult {
    data class Success(
        val serverName: String,
        val toolCount: Int,
        val toolNames: List<String>
    ) : McpConnectResult()

    data class Error(val message: String) : McpConnectResult()
}
