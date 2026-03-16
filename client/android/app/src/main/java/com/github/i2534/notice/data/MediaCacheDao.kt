package com.github.i2534.notice.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaCacheEntity)

    @Query("SELECT * FROM media_cache WHERE mediaUrl = :url LIMIT 1")
    suspend fun getByUrl(url: String): MediaCacheEntity?

    @Query("SELECT * FROM media_cache WHERE mediaUrl IN (:urls)")
    suspend fun getByUrls(urls: List<String>): List<MediaCacheEntity>

    @Query("DELETE FROM media_cache WHERE mediaUrl = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM media_cache WHERE mediaUrl IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("DELETE FROM media_cache")
    suspend fun deleteAll()

    @Query("SELECT localPath FROM media_cache")
    suspend fun getAllLocalPaths(): List<String>
}
