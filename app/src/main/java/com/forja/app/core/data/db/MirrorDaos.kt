package com.forja.app.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Mirror (v9): DAO-urile tabelelor noi. Pachetul D scrie focus_sessions / breath_sessions, pachetul C game_plays.

@Dao
interface FocusSessionDao {
    @Insert suspend fun insert(s: FocusSessionEntity): Long
    @Update suspend fun update(s: FocusSessionEntity)
    @Query("SELECT * FROM focus_sessions WHERE id = :id") suspend fun byId(id: Long): FocusSessionEntity?
    @Query("SELECT * FROM focus_sessions WHERE startAt >= :since ORDER BY startAt DESC") fun since(since: Long): Flow<List<FocusSessionEntity>>
    @Query("SELECT * FROM focus_sessions WHERE startAt >= :from AND startAt < :to ORDER BY startAt") suspend fun between(from: Long, to: Long): List<FocusSessionEntity>
}

@Dao
interface BreathSessionDao {
    @Insert suspend fun insert(s: BreathSessionEntity): Long
    @Query("SELECT * FROM breath_sessions WHERE startAt >= :since ORDER BY startAt DESC") fun since(since: Long): Flow<List<BreathSessionEntity>>
    @Query("SELECT * FROM breath_sessions WHERE startAt >= :from AND startAt < :to ORDER BY startAt") suspend fun between(from: Long, to: Long): List<BreathSessionEntity>
}

@Dao
interface GamePlayDao {
    @Insert suspend fun insert(p: GamePlayEntity): Long
    @Query("SELECT * FROM game_plays WHERE game = :game ORDER BY at DESC LIMIT :limit") suspend fun recent(game: String, limit: Int): List<GamePlayEntity>
    @Query("SELECT * FROM game_plays WHERE at >= :since ORDER BY at DESC") fun since(since: Long): Flow<List<GamePlayEntity>>
    /** Păstrează ultimele `keep` jocuri (jurnalul nu crește la nesfârșit). */
    @Query("DELETE FROM game_plays WHERE id NOT IN (SELECT id FROM game_plays ORDER BY at DESC LIMIT :keep)") suspend fun trim(keep: Int)
}
