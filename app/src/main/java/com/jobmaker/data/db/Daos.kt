package com.jobmaker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profil WHERE id = :id LIMIT 1")
    fun observe(id: Int = ProfileEntity.SINGLETON_ID): Flow<ProfileEntity?>

    @Query("SELECT * FROM profil WHERE id = :id LIMIT 1")
    suspend fun get(id: Int = ProfileEntity.SINGLETON_ID): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileEntity)
}

@Dao
interface CandidatureDao {
    @Query("SELECT * FROM candidatures ORDER BY modifieLe DESC")
    fun observeAll(): Flow<List<CandidatureEntity>>

    @Query("SELECT * FROM candidatures WHERE id = :id LIMIT 1")
    fun observeOne(id: String): Flow<CandidatureEntity?>

    @Query("SELECT * FROM candidatures WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CandidatureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CandidatureEntity)

    @Query("DELETE FROM candidatures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM candidatures")
    suspend fun count(): Int
}

@Dao
interface ExplanationDao {
    @Query("SELECT * FROM explications ORDER BY creeLe DESC")
    fun observeAll(): Flow<List<ExplanationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExplanationEntity)

    @Query("DELETE FROM explications WHERE id = :id")
    suspend fun delete(id: String)
}
