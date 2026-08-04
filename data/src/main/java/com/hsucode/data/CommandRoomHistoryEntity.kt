package com.hsucode.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "command_room_runs",
    indices = [Index("startedAt")]
)
data class CommandRoomRunEntity(
    @PrimaryKey val runId: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    val outcome: String = "running",
    val assignmentCount: Int = 0
)

@Entity(
    tableName = "command_room_events",
    indices = [Index("runId"), Index("workerRunId")]
)
data class CommandRoomEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val workerRunId: String = "",
    val agent: String = "",
    val type: String,
    val status: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
