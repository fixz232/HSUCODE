/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: adds encrypted, portable backup and restore support for HSUCODE-owned data.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.app

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.hsucode.data.AppDatabase
import com.hsucode.security.KeystoreProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected, portable backup of all HSUCODE-owned user data. */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val FORMAT = "hsucode.backup.v1"
    // Keep the portable archive contract aligned with Room's current schema.
    private const val DB_SCHEMA_VERSION = 42
    private const val MAX_ENTRY_COUNT = 20_000
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val PENDING_DIR = "restore_pending"
    private const val RESTORED_SECRETS = "restore_portable_secrets.json"
    private val secretSettingKeys = Regex("(^|\\.)(aux_.+_api_key|stt_api_key|search_api_key|git_token_enc)$")

    data class ExportSummary(val bytes: Long, val attachmentCount: Int)
    data class RestorePreview(val appVersion: String, val createdAt: Long, val attachmentCount: Int)

    suspend fun export(
        context: Context,
        database: AppDatabase,
        keystore: KeystoreProvider,
        destination: Uri,
        password: CharArray,
        appVersion: String
    ): ExportSummary = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "备份密码至少需要 8 位" }
        val tempDir = File(context.cacheDir, "backup-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbSnapshot = File(tempDir, AppDatabase.DB_NAME)
            checkpointAndCopyDatabase(context, database, dbSnapshot)
            val portableSecrets = collectPortableSecrets(database, keystore)
            val attachments = File(context.filesDir, "attachments")
                .walkTopDown().filter { it.isFile }.toList()
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("package", context.packageName)
                .put("appVersion", appVersion)
                .put("schemaVersion", DB_SCHEMA_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("databaseSha256", sha256(dbSnapshot))
                .put("attachmentCount", attachments.size)
                .put("credentialsPortable", true)

            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("无法打开备份文件")
            val written = CountingOutputStream(BufferedOutputStream(output)).use { counting ->
                BackupCrypto.encryptedOutput(counting, password).use { encrypted ->
                    ZipOutputStream(BufferedOutputStream(encrypted)).use { zip ->
                        zip.putFile("database/${AppDatabase.DB_NAME}", dbSnapshot)
                        zip.putText("portable-secrets.json", portableSecrets.toString())
                        attachments.forEach { file ->
                            val relative = file.relativeTo(File(context.filesDir, "attachments"))
                                .invariantSeparatorsPath
                            zip.putFile("files/attachments/$relative", file)
                        }
                        zip.putText("manifest.json", manifest.toString())
                    }
                }
                counting.count
            }
            ExportSummary(written, attachments.size)
        } finally {
            tempDir.deleteRecursively()
            password.fill('\u0000')
        }
    }

    suspend fun stageRestore(
        context: Context,
        source: Uri,
        password: CharArray
    ): RestorePreview = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "备份密码至少需要 8 位" }
        val root = context.noBackupFilesDir
        val staging = File(root, "restore_staging_${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("无法读取备份文件")
            extractEncryptedArchive(input, password, staging)
            val manifestFile = File(staging, "manifest.json")
            val dbFile = File(staging, "database/${AppDatabase.DB_NAME}")
            require(manifestFile.isFile && dbFile.isFile) { "备份缺少数据库或清单" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.optString("format") == FORMAT) { "不是受支持的 HSUCODE 备份" }
            require(manifest.optString("package") == context.packageName) { "备份不属于当前应用" }
            val schema = manifest.optInt("schemaVersion", -1)
            require(schema in 5..DB_SCHEMA_VERSION) { "备份数据库版本不兼容: $schema" }
            require(sha256(dbFile) == manifest.optString("databaseSha256")) { "数据库校验失败,备份可能已损坏" }
            validateSqlite(dbFile, schema)

            val pending = File(root, PENDING_DIR)
            val replacement = File(root, "${PENDING_DIR}_new")
            replacement.deleteRecursively()
            require(staging.renameTo(replacement)) { "无法暂存恢复数据" }
            pending.deleteRecursively()
            require(replacement.renameTo(pending)) { "无法提交恢复任务" }
            RestorePreview(
                appVersion = manifest.optString("appVersion", "未知"),
                createdAt = manifest.optLong("createdAt"),
                attachmentCount = manifest.optInt("attachmentCount")
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw when (t) {
                is SecurityException, is IllegalArgumentException -> t
                else -> IllegalArgumentException("备份密码错误或文件已损坏", t)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Called before Room is opened. Existing data is retained as a recoverable pre-restore copy. */
    fun applyPendingRestore(context: Context): Boolean {
        val pending = File(context.noBackupFilesDir, PENDING_DIR)
        val stagedDb = File(pending, "database/${AppDatabase.DB_NAME}")
        if (!stagedDb.isFile) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rollback = File(context.noBackupFilesDir, "pre_restore_$stamp").apply { mkdirs() }
        val currentDb = context.getDatabasePath(AppDatabase.DB_NAME)
        val currentAttachments = File(context.filesDir, "attachments")
        try {
            currentDb.parentFile?.mkdirs()
            listOf("", "-wal", "-shm").forEach { suffix ->
                val current = File(currentDb.path + suffix)
                if (current.isFile) current.copyTo(File(rollback, current.name), overwrite = true)
                current.delete()
            }
            stagedDb.copyTo(currentDb, overwrite = true)

            val stagedAttachments = File(pending, "files/attachments")
            if (currentAttachments.exists()) {
                currentAttachments.copyRecursively(File(rollback, "attachments"), overwrite = true)
                currentAttachments.deleteRecursively()
            }
            if (stagedAttachments.exists()) {
                stagedAttachments.copyRecursively(currentAttachments, overwrite = true)
            } else {
                currentAttachments.mkdirs()
            }

            val secrets = File(pending, "portable-secrets.json")
            if (secrets.isFile) {
                secrets.copyTo(File(context.noBackupFilesDir, RESTORED_SECRETS), overwrite = true)
            }
            pending.deleteRecursively()
            File(context.noBackupFilesDir, "last_restore.txt").writeText(
                "restoredAt=${System.currentTimeMillis()}\nrollback=${rollback.absolutePath}\n"
            )
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "restore apply failed, rolling back", t)
            runCatching {
                File(context.noBackupFilesDir, RESTORED_SECRETS).delete()
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val target = File(currentDb.path + suffix)
                    target.delete()
                    val saved = File(rollback, currentDb.name + suffix)
                    if (saved.isFile) saved.copyTo(target, overwrite = true)
                }
                val savedAttachments = File(rollback, "attachments")
                currentAttachments.deleteRecursively()
                if (savedAttachments.exists()) {
                    savedAttachments.copyRecursively(currentAttachments, overwrite = true)
                }
            }
            return false
        }
    }

    /** Re-encrypt credentials with this device's Android Keystore after a cross-device restore. */
    suspend fun applyRestoredSecrets(context: Context, database: AppDatabase, keystore: KeystoreProvider) {
        withContext(Dispatchers.IO) {
            val file = File(context.noBackupFilesDir, RESTORED_SECRETS)
            if (!file.isFile) return@withContext
            val json = JSONObject(file.readText())
            database.withTransaction {
                    val providers = json.optJSONArray("providers") ?: JSONArray()
                    for (i in 0 until providers.length()) {
                        val item = providers.getJSONObject(i)
                        database.providerConfigDao().getById(item.getLong("id"))?.let { cfg ->
                            val encrypted = Base64.encodeToString(
                                keystore.encrypt(item.optString("secret")), Base64.NO_WRAP
                            )
                            database.providerConfigDao().update(cfg.copy(apiKeyEnc = encrypted))
                        }
                    }
                    val settings = json.optJSONObject("settings") ?: JSONObject()
                    settings.keys().forEach { key ->
                        val encrypted = Base64.encodeToString(
                            keystore.encrypt(settings.optString(key)), Base64.NO_WRAP
                        )
                        database.settingDao().put(key, encrypted)
                    }
                    val mcp = json.optJSONArray("mcp") ?: JSONArray()
                    for (i in 0 until mcp.length()) {
                        val item = mcp.getJSONObject(i)
                        database.mcpServerDao().getById(item.getLong("id"))?.let { server ->
                            database.mcpServerDao().update(server.copy(
                                authHeader = keystore.encryptString(item.optString("authHeader")),
                                envJson = keystore.encryptString(item.optString("envJson"))
                            ))
                        }
                    }
            }
            file.delete()
        }
    }

    fun restartApplication(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        val pending = PendingIntent.getActivity(
            context, 9917, launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 700, pending)
        (context as? Activity)?.finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private fun checkpointAndCopyDatabase(context: Context, database: AppDatabase, destination: File) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) error("数据库正忙,请稍后重试")
        }
        database.runInTransaction {
            val source = context.getDatabasePath(AppDatabase.DB_NAME)
            require(source.isFile) { "数据库文件不存在" }
            source.copyTo(destination, overwrite = true)
        }
    }

    private suspend fun collectPortableSecrets(database: AppDatabase, keystore: KeystoreProvider): JSONObject {
        val providers = JSONArray()
        database.providerConfigDao().getAll().forEach { cfg ->
            if (cfg.apiKeyEnc.isNotBlank()) {
                decryptLegacy(keystore, cfg.apiKeyEnc)?.let {
                    providers.put(JSONObject().put("id", cfg.id).put("secret", it))
                }
            }
        }
        val settings = JSONObject()
        database.settingDao().getByPrefix("").forEach { item ->
            if (secretSettingKeys.containsMatchIn(item.key) && item.value.isNotBlank()) {
                decryptLegacy(keystore, item.value)?.let { settings.put(item.key, it) }
            }
        }
        val mcp = JSONArray()
        database.mcpServerDao().getAll().forEach { server ->
            if (server.authHeader.isNotBlank() || server.envJson.isNotBlank()) {
                mcp.put(JSONObject()
                    .put("id", server.id)
                    .put("authHeader", runCatching { keystore.decryptString(server.authHeader) }.getOrDefault(""))
                    .put("envJson", runCatching { keystore.decryptString(server.envJson) }.getOrDefault("")))
            }
        }
        return JSONObject().put("providers", providers).put("settings", settings).put("mcp", mcp)
    }

    private fun decryptLegacy(keystore: KeystoreProvider, value: String): String? = runCatching {
        keystore.decrypt(Base64.decode(value, Base64.NO_WRAP))
    }.getOrNull()

    private fun extractEncryptedArchive(input: InputStream, password: CharArray, destination: File) {
        BackupCrypto.decryptedInput(BufferedInputStream(input), password).use { decrypted ->
            ZipInputStream(BufferedInputStream(decrypted)).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRY_COUNT) { "备份条目过多" }
                    val name = safeEntryName(entry.name)
                    require(
                        name == "manifest.json" || name == "portable-secrets.json" ||
                            name == "database/${AppDatabase.DB_NAME}" || name.startsWith("files/attachments/")
                    ) { "备份包含未知路径: $name" }
                    if (!entry.isDirectory) {
                        val out = File(destination, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { target ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_TOTAL_BYTES) { "备份解压后超过 1 GB 限制" }
                                target.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun safeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." || it.isBlank() }) {
            "备份路径不安全"
        }
        return normalized
    }

    private fun validateSqlite(file: File, expectedSchema: Int) {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        db.use {
            it.rawQuery("PRAGMA integrity_check", null).use { c ->
                require(c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)) { "数据库完整性检查失败" }
            }
            it.rawQuery("PRAGMA user_version", null).use { c ->
                require(c.moveToFirst() && c.getInt(0) == expectedSchema) { "数据库版本与清单不一致" }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { it.copyTo(this, 64 * 1024) }
        closeEntry()
    }

    private class CountingOutputStream(out: OutputStream) : java.io.FilterOutputStream(out) {
        var count: Long = 0
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}

internal object BackupCrypto {
    private val MAGIC = "HSUCODE-BACKUP".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256

    fun encryptedOutput(output: OutputStream, password: CharArray): OutputStream {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val data = DataOutputStream(output)
        data.write(MAGIC)
        data.writeByte(VERSION)
        data.write(salt)
        data.write(iv)
        data.flush()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return CipherOutputStream(output, cipher)
    }

    fun decryptedInput(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "不是 HSUCODE 备份文件" }
        require(data.readUnsignedByte() == VERSION) { "不支持的备份版本" }
        val salt = ByteArray(16).also(data::readFully)
        val iv = ByteArray(12).also(data::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return CipherInputStream(input, cipher)
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
