/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = SkillEntity.TABLE_NAME,
    indices = [Index(value = ["keyword"], unique = true)]
)
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val keyword: String,
    val answer: String
) {
    companion object {
        const val TABLE_NAME = "SkillEntity"
    }
}

@Fts4(contentEntity = SkillEntity::class)
@Entity(tableName = SkillFtsEntity.TABLE_NAME)
data class SkillFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    val keyword: String,
    val answer: String
) {
    companion object {
        const val TABLE_NAME = "SkillEntityFts"
    }
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(skills: List<SkillEntity>): List<Long>

    @Transaction
    suspend fun replaceAll(skills: List<SkillEntity>) {
        clear()
        upsertAll(skills)
        rebuildFts()
    }

    @Transaction
    suspend fun addOrReplaceAll(skills: List<SkillEntity>) {
        upsertAll(skills)
        rebuildFts()
    }

    @Query(
        """
        SELECT * FROM SkillEntity
        WHERE keyword LIKE '%' || :query || '%'
           OR :query LIKE '%' || keyword || '%'
        ORDER BY length(keyword) DESC, id ASC
        """
    )
    suspend fun searchSkills(query: String): List<SkillEntity>

    @Query(
        """
        SELECT SkillEntity.* FROM SkillEntity
        JOIN SkillEntityFts ON SkillEntity.id = SkillEntityFts.rowid
        WHERE SkillEntityFts MATCH :query
        ORDER BY length(SkillEntity.keyword) DESC, SkillEntity.id ASC
        LIMIT :limit
        """
    )
    suspend fun searchSkillsFts(query: String, limit: Int = 20): List<SkillEntity>

    @Query(
        """
        SELECT * FROM SkillEntity
        WHERE keyword LIKE '%' || :query || '%'
           OR :query LIKE '%' || keyword || '%'
        ORDER BY length(keyword) DESC, id ASC
        LIMIT 1
        """
    )
    suspend fun fuzzyFind(query: String): SkillEntity?

    @Query("DELETE FROM SkillEntity")
    suspend fun clear()

    @Query("INSERT INTO SkillEntityFts(SkillEntityFts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

@Database(
    entities = [SkillEntity::class, SkillFtsEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LocalSkillDatabase : RoomDatabase() {
    abstract fun skillDao(): SkillDao

    companion object {
        private val QuerySplitRegex =
            Regex("\\s+|[\\uFF0C\\u3002\\uFF01\\uFF1F\\u3001\\uFF1B\\uFF1A,.!?;:\\[\\]\\uFF08\\uFF09(){}<>\"'`]+")

        @Volatile
        private var instance: LocalSkillDatabase? = null

        fun getInstance(context: Context): LocalSkillDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalSkillDatabase::class.java,
                    "ai_local_skills.db"
                )
                    .build()
                    .also { instance = it }
            }
        }

        fun likeQuery(rawText: String): String {
            val normalized = rawText
                .trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            return "%$normalized%"
        }

        fun keywordQuery(keyword: String): String {
            return likeQuery(keyword)
        }

        fun ftsQuery(rawText: String): String {
            return rawText
                .trim()
                .split(QuerySplitRegex)
                .asSequence()
                .map { it.trim() }
                .filter { it.length >= 2 }
                .take(8)
                .joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        }
    }
}
