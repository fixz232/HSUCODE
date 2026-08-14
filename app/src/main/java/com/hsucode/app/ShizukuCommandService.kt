package com.hsucode.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs only as the Shizuku user-service process (shell UID), never as the app UID. */
class ShizukuCommandService : Service() {
    companion object {
        const val DESCRIPTOR = "com.hsucode.app.IShizukuCommandService"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                true
            }
            TRANSACTION_EXECUTE -> {
                data.enforceInterface(DESCRIPTOR)
                val result = executeCommand(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeString(result)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun executeCommand(command: String): String {
        if (command.isBlank()) return JSONObject().put("exitCode", 2).put("stderr", "命令不能为空").toString()
            var process: Process? = null
            return try {
                process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()
                val stdout = StringBuilder()
                val stderr = StringBuilder()
                val stdoutReader = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }.also { it.start() }
                val stderrReader = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }.also { it.start() }
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    JSONObject().put("exitCode", 124).put("stderr", "命令超时（30 秒）").toString()
                } else {
                    stdoutReader.join(); stderrReader.join()
                    JSONObject().put("exitCode", process.exitValue()).put("stdout", stdout.toString()).put("stderr", stderr.toString()).toString()
                }
            } catch (error: Exception) {
                runCatching { process?.destroyForcibly() }
                JSONObject().put("exitCode", -1).put("stderr", error.message.orEmpty()).toString()
            }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
