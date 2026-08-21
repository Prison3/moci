package com.moci.words.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Query("SELECT * FROM api_cache WHERE userId = :userId AND cacheKey = :key LIMIT 1")
    suspend fun get(userId: Int, key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CacheEntry)

    @Query("DELETE FROM api_cache WHERE userId = :userId AND cacheKey = :key")
    suspend fun delete(userId: Int, key: String)

    @Query("DELETE FROM api_cache WHERE userId = :userId AND cacheKey LIKE :pattern")
    suspend fun deleteByPrefix(userId: Int, pattern: String)

    @Query("DELETE FROM api_cache WHERE userId = :userId")
    suspend fun deleteUser(userId: Int)

    @Query("DELETE FROM api_cache")
    suspend fun clearAll()
}
