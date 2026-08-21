package com.moci.words.db

/**
 * 本地 Room 缓存：按用户 + key 存 API JSON。
 * 默认 30 分钟内命中则不再打服务器；写操作后由 ApiClient 主动失效。
 */
class ApiCache(private val dao: CacheDao) {

    companion object {
        /** 默认新鲜期：半小时内切 Tab 直接读本地。 */
        const val DEFAULT_MAX_AGE_MS = 30L * 60L * 1000L
    }

    suspend fun read(userId: Int, key: String, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): String? {
        val row = dao.get(userId, key) ?: return null
        if (maxAgeMs != Long.MAX_VALUE && System.currentTimeMillis() - row.updatedAt > maxAgeMs) {
            return null
        }
        return row.body
    }

    suspend fun write(userId: Int, key: String, body: String) {
        dao.upsert(
            CacheEntry(
                userId = userId,
                cacheKey = key,
                body = body,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun remove(userId: Int, key: String) {
        dao.delete(userId, key)
    }

    suspend fun removePrefix(userId: Int, prefix: String) {
        dao.deleteByPrefix(userId, "$prefix%")
    }

    suspend fun clearUser(userId: Int) {
        dao.deleteUser(userId)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
