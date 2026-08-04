package com.hsucode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandRoomHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: CommandRoomRunEntity)

    @Insert
    suspend fun insertEvent(event: CommandRoomEventEntity): Long

    @Query("UPDATE command_room_runs SET outcome = :outcome, endedAt = :endedAt WHERE runId = :runId")
    suspend fun finishRun(runId: String, outcome: String, endedAt: Long = System.currentTimeMillis())

    @Query("UPDATE command_room_runs SET outcome = 'running', endedAt = 0 WHERE runId = :runId")
    suspend fun reopenRun(runId: String)

    @Query("UPDATE command_room_runs SET outcome = 'interrupted', endedAt = :endedAt WHERE outcome = 'running'")
    suspend fun markRunningAsInterrupted(endedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM command_room_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int = 20): Flow<List<CommandRoomRunEntity>>

    @Query("SELECT * FROM command_room_events WHERE runId = :runId ORDER BY id ASC")
    fun observeEvents(runId: String): Flow<List<CommandRoomEventEntity>>

    @Query("DELETE FROM command_room_events WHERE runId IN (SELECT runId FROM command_room_runs ORDER BY startedAt DESC LIMIT -1 OFFSET :keepRuns)")
    suspend fun deleteEventsOutsideRecentRuns(keepRuns: Int = 100)

    @Query("DELETE FROM command_room_runs WHERE runId IN (SELECT runId FROM command_room_runs ORDER BY startedAt DESC LIMIT -1 OFFSET :keepRuns)")
    suspend fun deleteRunsOutsideRecentRuns(keepRuns: Int = 100)
}
