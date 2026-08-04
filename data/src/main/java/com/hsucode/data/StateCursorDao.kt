/*
 * Modification notice (2026-08-04 17:26 UTC+08:00): HSUCODE is a modified work based on
 * https://github.com/kusesad-1122/XINCODE-Public.
 * Change: exposes saved task cursors for backup and recovery workflows.
 * Existing copyright, license, and author notices are retained.
 */
package com.hsucode.data

import androidx.room.*

@Dao
interface StateCursorDao {
    @Query("SELECT * FROM state_cursor LIMIT 1")
    suspend fun getAny(): StateCursorEntity?

    @Query("SELECT * FROM state_cursor WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: Long): StateCursorEntity?

    @Query("SELECT * FROM state_cursor ORDER BY updatedAt DESC")
    suspend fun getAll(): List<StateCursorEntity>

    @Query("SELECT * FROM state_cursor ORDER BY updatedAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<StateCursorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: StateCursorEntity)

    @Query("DELETE FROM state_cursor WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    @Query("DELETE FROM state_cursor")
    suspend fun deleteAll()
}
