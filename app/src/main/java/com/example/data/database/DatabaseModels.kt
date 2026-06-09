package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "github_config")
data class GitHubConfig(
    @PrimaryKey val id: Int = 1, // Only 1 configuration row
    val username: String = "",
    val token: String = "",
    val repositoryName: String = "", // e.g., "username/repo"
    val branchName: String = "main",
    val isAutoSyncEnabled: Boolean = false,
    val fileSyncMode: String = "ZIP" // "ZIP" or "SOURCE_TREE"
)

@Entity(tableName = "sketchware_projects")
data class SketchwareProject(
    @PrimaryKey val id: String, // Project ID e.g., "601"
    val name: String,
    val packageName: String,
    val versionName: String,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncStatus: String = "NEVER", // "SUCCESS", "FAILED", "NEVER"
    val localPath: String, // Path on device
    val isAutoSyncEnabled: Boolean = false,
    val fixColorsOnSync: Boolean = true,
    val fixManifestOnSync: Boolean = true
)

@Entity(tableName = "sync_logs")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val projectName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // "SUCCESS", "FAILED"
    val message: String
)
