package com.example.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.data.database.GitHubConfig
import com.example.data.database.SketchwareProject
import com.example.data.database.SyncDao
import com.example.data.database.SyncLog
import com.example.service.GitHubService
import com.example.service.SketchwareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File

class SyncRepository(
    private val context: Context,
    private val syncDao: SyncDao
) {
    private val gitHubService = GitHubService()
    private val TAG = "SyncRepository"

    // Flows
    val gitHubConfig: Flow<GitHubConfig?> = syncDao.getGitHubConfigFlow()
    val allProjects: Flow<List<SketchwareProject>> = syncDao.getAllProjectsFlow()
    val syncLogs: Flow<List<SyncLog>> = syncDao.getAllLogsFlow()

    // Configuration
    suspend fun saveGitHubConfig(config: GitHubConfig) = withContext(Dispatchers.IO) {
        syncDao.saveGitHubConfig(config)
    }

    suspend fun getGitHubConfig(): GitHubConfig? = withContext(Dispatchers.IO) {
        syncDao.getGitHubConfig()
    }

    // Projects CRUD
    suspend fun insertProject(project: SketchwareProject) = withContext(Dispatchers.IO) {
        syncDao.insertProject(project)
    }

    suspend fun updateProject(project: SketchwareProject) = withContext(Dispatchers.IO) {
        syncDao.updateProject(project)
    }

    suspend fun deleteProject(project: SketchwareProject) = withContext(Dispatchers.IO) {
        syncDao.deleteProject(project)
    }

    suspend fun deleteProjectById(id: String) = withContext(Dispatchers.IO) {
        syncDao.deleteProjectById(id)
    }

    // Logs management
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        syncDao.clearLogs()
    }

    /**
     * Scans a specific device folder for any Sketchware project directory configurations.
     * Imports new ones automatically or lists them for manual confirmation.
     */
    suspend fun scanAndImportSketchwareProjects(customPath: String? = null): List<SketchwareProject> = withContext(Dispatchers.IO) {
        // Use default Sketchware path or user customized path
        val defaultPath = "/storage/emulated/0/sketchware/export_src/"
        val pathToScan = if (customPath.isNullOrBlank()) defaultPath else customPath

        val folder = File(pathToScan)
        Log.d(TAG, "Scanning for Sketchware projects at: ${folder.absolutePath}")

        val foundMaps = SketchwareUtils.scanLocalSketchwareProjects(folder)
        val importedProjects = mutableListOf<SketchwareProject>()

        for (map in foundMaps) {
            val id = map["id"] ?: continue
            val name = map["name"] ?: "Project $id"
            val pkg = map["packageName"] ?: "com.example.project"
            val ver = map["versionName"] ?: "1.0"
            val path = map["localPath"] ?: ""

            // Construct entity structure matching details
            val entity = SketchwareProject(
                id = id,
                name = name,
                packageName = pkg,
                versionName = ver,
                localPath = path,
                lastSyncStatus = "NEVER"
            )

            // Auto insert into Room
            syncDao.insertProject(entity)
            importedProjects.add(entity)
        }

        return@withContext importedProjects
    }

    /**
     * Executes the cloud backup synchronization for a single project.
     * Integrates API call and logs results directly to local DB files.
     */
    suspend fun runProjectSync(
        projectId: String,
        progressListener: GitHubService.UploadProgress
    ) = withContext(Dispatchers.IO) {
        val project = syncDao.getProjectById(projectId)
        if (project == null) {
            progressListener.onError("مشروع سكيتشوير المحدد غير موجود في قاعدة البيانات المحلية.")
            progressListener.onFinished(false)
            return@withContext
        }

        val config = syncDao.getGitHubConfig()
        if (config == null || config.token.isBlank() || config.repositoryName.isBlank()) {
            progressListener.onError("لم يتم تكوين إعدادات مستودع GitHub بالشكل الصحيح. الرمز المميز (Token) أو المستودع فارغ.")
            progressListener.onFinished(false)
            return@withContext
        }

        progressListener.onMessage("جاري بدء المزامنة للمشروع: ${project.name}...")

        // Execute sync using GitHub Service
        gitHubService.syncSketchwareProject(
            token = config.token,
            repoOwnerAndName = config.repositoryName,
            branch = config.branchName,
            projectFolder = File(project.localPath),
            projectName = project.name,
            projectId = project.id,
            syncMode = config.fileSyncMode, // "ZIP" or "SOURCE_TREE"
            fixColors = project.fixColorsOnSync,
            fixManifest = project.fixManifestOnSync,
            progressListener = object : GitHubService.UploadProgress {
                override fun onProgress(current: Int, total: Int, fileName: String) {
                    progressListener.onProgress(current, total, fileName)
                }

                override fun onMessage(message: String) {
                    progressListener.onMessage(message)
                }

                override fun onError(message: String) {
                    progressListener.onError(message)
                    // Log failed attempt
                    recordSyncLog(project, "FAILED", message)
                }

                override fun onFinished(success: Boolean) {
                    // Update database status values based on success
                    val updatedState = project.copy(
                        lastSyncTimestamp = System.currentTimeMillis(),
                        lastSyncStatus = if (success) "SUCCESS" else "FAILED"
                    )
                    // We must run update within synchronous blocks
                    kotlinx.coroutines.runBlocking {
                        syncDao.updateProject(updatedState)
                        if (success) {
                            recordSyncLog(project, "SUCCESS", "تمت مزامنة مشروع ${project.name} بنجاح إلى مستودع GitHub في فرع ${config.branchName}.")
                        }
                    }
                    progressListener.onFinished(success)
                }
            }
        )
    }

    private fun recordSyncLog(project: SketchwareProject, status: String, message: String) {
        kotlinx.coroutines.runBlocking {
            syncDao.insertLog(
                SyncLog(
                    projectId = project.id,
                    projectName = project.name,
                    status = status,
                    message = message
                )
            )
        }
    }
}
