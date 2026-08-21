package com.moci.words.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
abstract class MociDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile
        private var instance: MociDatabase? = null

        fun get(context: Context): MociDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MociDatabase::class.java,
                    "moci_cache.db",
                ).build().also { instance = it }
            }
    }
}
