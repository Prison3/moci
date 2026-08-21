package com.moci.words.db

import androidx.room.Entity

/** 按用户隔离的 API 响应缓存行。 */
@Entity(tableName = "api_cache", primaryKeys = ["userId", "cacheKey"])
data class CacheEntry(
    val userId: Int,
    val cacheKey: String,
    val body: String,
    val updatedAt: Long,
)
