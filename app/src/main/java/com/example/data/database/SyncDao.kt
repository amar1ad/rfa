package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    // GitHub Config
    @Query("SELECT * FROM github_config WHERE id = 1 LIMIT 1")
    fun getGitHubConfigFlow(): Flow<GitHubConfig?>

    @Query("SELECT * FROM github_config WHERE id = 1 LIMIT 1")
    suspend fun getGitHubConfig(): GitHubConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGitHubConfig(config: GitHubConfig)

    // Sketchware Projects
    @Query("SELECT * FROM sketchware_projects ORDER BY name ASC")
    fun getAllProjectsFlow(): Flow<List<SketchwareProject>>

    @Query("SELECT * FROM sketchware_projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): SketchwareProject?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: SketchwareProject)

    @Update
    suspend fun updateProject(project: SketchwareProject)

    @Delete
    suspend fun deleteProject(project: SketchwareProject)

    @Query("DELETE FROM sketchware_projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    // Sync Logs
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllLogsFlow(): Flow<List<SyncLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SyncLog)

    @Query("DELETE FROM sync_logs")
    suspend fun clearLogs()
}
