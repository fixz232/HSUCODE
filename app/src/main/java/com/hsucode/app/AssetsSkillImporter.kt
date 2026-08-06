package com.hsucode.app

import android.content.Context
import android.util.Log
import com.hsucode.data.AppDatabase
import com.hsucode.data.SkillEntity

/** Installs bundled SKILL.md files into the local skill store on every startup. */
object AssetsSkillImporter {
    private const val TAG = "AssetsSkillImporter"
    private const val ASSET_ROOT = "skills"

    suspend fun install(context: Context, database: AppDatabase): Int {
        val dirs = try {
            context.assets.list(ASSET_ROOT) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "assets list failed: ${e.message}")
            emptyArray()
        }
        if (dirs.isEmpty()) return 0

        val dao = database.skillDao()
        var count = 0
        for (dir in dirs) {
            val path = "$ASSET_ROOT/$dir/SKILL.md"
            val text = try {
                context.assets.open(path).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                continue
            }
            val (name, desc, content) = SkillImporter.parse(text, dir)
            val existing = dao.getByName(name)
            dao.upsert(
                SkillEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    description = desc,
                    content = content,
                    source = "bundled",
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            count++
        }
        if (count > 0) Log.i(TAG, "imported $count bundled skills from assets")
        return count
    }
}
