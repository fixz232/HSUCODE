package com.hsucode.app

import com.hsucode.data.MemoryEntity

/** Deterministic relationship maintenance without a second vector database. */
object MemoryRelations {
    fun relatedByTags(memories: List<MemoryEntity>): Map<Long, Set<Long>> {
        val result = memories.associate { it.id to linkedSetOf<Long>() }.toMutableMap()
        val buckets = memories.flatMap { memory -> memory.tags.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.map { it to memory.id } }.groupBy({ it.first }, { it.second })
        buckets.values.filter { it.size > 1 }.forEach { ids -> ids.forEach { id -> result.getValue(id).addAll(ids.filterNot { it == id }) } }
        return result
    }

    fun withRelationTags(memory: MemoryEntity, relations: Set<Long>): MemoryEntity {
        val preserved = memory.tags.split(',').map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("related:") }
        return memory.copy(tags = (preserved + relations.sorted().map { "related:$it" }).distinct().joinToString(","), updatedAt = System.currentTimeMillis())
    }
}
