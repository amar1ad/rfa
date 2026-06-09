package com.example.service

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val TAG = "GitHubService"

    interface UploadProgress {
        fun onProgress(current: Int, total: Int, fileName: String)
        fun onMessage(message: String)
        fun onError(message: String)
        fun onFinished(success: Boolean)
    }

    /**
     * Checks if a file exists on GitHub and returns its SHA if present.
     * Returns null if file does not exist, or throws Exception if request fails.
     */
    private fun getFileSha(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        filePath: String
    ): String? {
        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$filePath?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val json = JSONObject(response.body?.string() ?: "")
                return json.optString("sha", null)
            } else if (response.code == 404) {
                return null
            } else {
                throw IOException("GitHub API SHA request failed with code: ${response.code}, message: ${response.message}")
            }
        }
    }

    /**
     * Uploads/Overwrites a single file to GitHub with Base64 content.
     */
    fun uploadFile(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        filePath: String,
        contentBytes: ByteArray,
        commitMessage: String
    ): Boolean {
        // 1. Get SHA if file already exists on GitHub
        val sha = try {
            getFileSha(token, repoOwnerAndName, branch, filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed retrieving file SHA for $filePath, attempting initial commit", e)
            null
        }

        // 2. Base64 encode file contents
        val base64Content = Base64.encodeToString(contentBytes, Base64.NO_WRAP)

        // 3. Build PUT payload
        val jsonPayload = JSONObject().apply {
            put("message", commitMessage)
            put("content", base64Content)
            put("branch", branch)
            if (sha != null) {
                put("sha", sha)
            }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$filePath"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200 || response.code == 201) {
                Log.d(TAG, "Uploaded file successfully: $filePath")
                return true
            } else {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Upload failed for $filePath: code=${response.code} body=$errorBody")
                throw IOException("Commit failed for $filePath with code: ${response.code}, error: $errorBody")
            }
        }
    }

    /**
     * Syncs a whole Sketchware project folder as either a combined ZIP backup or as an entire Source Code Tree.
     */
    fun syncSketchwareProject(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        projectFolder: File,
        projectName: String,
        projectId: String,
        syncMode: String, // "ZIP" or "SOURCE_TREE"
        fixColors: Boolean,
        fixManifest: Boolean,
        progressListener: UploadProgress
    ) {
        if (!projectFolder.exists()) {
            progressListener.onError("مجلد المشروع غير موجود: ${projectFolder.absolutePath}")
            progressListener.onFinished(false)
            return
        }

        val isZipSource = projectFolder.isFile && projectFolder.name.endsWith(".zip", ignoreCase = true)

        if (syncMode == "ZIP") {
            try {
                progressListener.onMessage("جاري تحضير النسخة الاحتياطية المضغوطة وتحسين محتواها...")
                // Temp output zip file
                val tempZip = File.createTempFile("sc_backup_", ".zip")
                tempZip.deleteOnExit()

                if (isZipSource) {
                    if (fixColors || fixManifest) {
                        progressListener.onMessage("جاري فك ضغط الأرشيف مؤقتاً لتصحيح الألوان والـ Manifest...")
                        val tempExtractDir = File(projectFolder.parentFile, "temp_zip_extract_${System.currentTimeMillis()}")
                        tempExtractDir.mkdirs()
                        SketchwareUtils.decompressZipToFolder(projectFolder, tempExtractDir)
                        
                        val compressed = SketchwareUtils.compressFolderToZip(tempExtractDir, tempZip, applyFixes = fixColors || fixManifest)
                        tempExtractDir.deleteRecursively()
                        if (!compressed) {
                            progressListener.onError("فشل في ضغط وتصفية الأرشيف.")
                            progressListener.onFinished(false)
                            return
                        }
                    } else {
                        projectFolder.copyTo(tempZip, overwrite = true)
                    }
                } else {
                    val compressed = SketchwareUtils.compressFolderToZip(projectFolder, tempZip, applyFixes = fixColors || fixManifest)
                    if (!compressed) {
                        progressListener.onError("فشل في ضغط وتجهيز مجلد النسخة الاحتياطية.")
                        progressListener.onFinished(false)
                        return
                    }
                }

                progressListener.onMessage("جاري رفع ملف النسخة الاحتياطية المضغوطة إلى GitHub...")
                val zipBytes = tempZip.readBytes()
                val targetName = "${projectName.replace(" ", "_")}_backup_${projectId}.zip"
                val repoPath = "backups/$targetName"

                val success = uploadFile(
                    token = token,
                    repoOwnerAndName = repoOwnerAndName,
                    branch = branch,
                    filePath = repoPath,
                    contentBytes = zipBytes,
                    commitMessage = "Backup Sketchware Project: $projectName (ID: $projectId) - Stable Backup"
                )

                if (success) {
                    progressListener.onMessage("تم رفع النسخة الاحتياطية ($targetName) بنجاح!")
                    try {
                        ensureWorkflowFileExists(token, repoOwnerAndName, branch)
                        progressListener.onMessage("تم إنشاء وتأكيد ملف البناء التلقائي .github/workflows/android-build.yml بنجاح!")
                        ensureBuildRunFileExists(token, repoOwnerAndName, branch)
                        progressListener.onMessage("تم تحديث ملف البناء التلقائي buildrun.txt بنجاح!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to automatically create workflow or trigger build: ${e.localizedMessage}")
                        progressListener.onMessage("ملاحظة: تعذر تكوين ملف البناء التلقائي حالياً: ${e.localizedMessage}")
                    }
                    progressListener.onFinished(true)
                } else {
                    progressListener.onError("فشل رفع الملف إلى المستودع.")
                    progressListener.onFinished(false)
                }

                // Clean-up
                tempZip.delete()
            } catch (e: Exception) {
                progressListener.onError("خطاء أثناء المزامنة: ${e.localizedMessage ?: "عطل غير معروف"}")
                progressListener.onFinished(false)
            }
        } else {
            // SOURCE_TREE SYNC: Push all files recursively
            var workingFolder = projectFolder
            var tempExtractDir: File? = null
            try {
                if (isZipSource) {
                    progressListener.onMessage("جاري استخراج ملفات الكود من الأرشيف المضغوط لتهيئتها للرفع الهيكلي...")
                    tempExtractDir = File(projectFolder.parentFile, "temp_src_extract_${System.currentTimeMillis()}")
                    tempExtractDir.mkdirs()
                    val extracted = SketchwareUtils.decompressZipToFolder(projectFolder, tempExtractDir)
                    if (!extracted) {
                        progressListener.onError("فشل في فك الأرشيف لتحديد شجرة ملفات السورس كود.")
                        progressListener.onFinished(false)
                        return
                    }
                    workingFolder = tempExtractDir
                }

                progressListener.onMessage("جاري فحص ملفات المشروع لتصحيح الألوان والصلاحيات والرفع كملفات برمجية...")
                val allFiles = mutableListOf<File>()
                gatherUploadFiles(workingFolder, workingFolder, allFiles)

                if (allFiles.isEmpty()) {
                    progressListener.onError("لا توجد ملفات صالحة للرفع في مجلد مشروع Sketchware.")
                    progressListener.onFinished(false)
                    return
                }

                val totalCount = allFiles.size
                progressListener.onMessage("تم العثور على $totalCount ملفاً. جاري الرفع التدريجي...")

                var successCount = 0
                for ((index, file) in allFiles.withIndex()) {
                    val relativePath = file.relativeTo(workingFolder).path
                    val fileName = file.name
                    progressListener.onProgress(index + 1, totalCount, relativePath)

                    try {
                        var fileBytes = file.readBytes()
                        // Apply automatic Sketchware XML structure repairs if required
                        if (fileName == "AndroidManifest.xml" && fixManifest) {
                            val rawText = String(fileBytes, Charsets.UTF_8)
                            val cleaned = SketchwareUtils.fixAndroidManifest(rawText)
                            fileBytes = cleaned.toByteArray(Charsets.UTF_8)
                            Log.d(TAG, "Processed modifications and cleaned AndroidManifest.xml before push")
                        } else if (fileName == "colors.xml" && fixColors) {
                            val rawText = String(fileBytes, Charsets.UTF_8)
                            val cleaned = SketchwareUtils.fixColorsXml(rawText)
                            fileBytes = cleaned.toByteArray(Charsets.UTF_8)
                            Log.d(TAG, "Processed modifications and cleaned colors.xml before push")
                        }

                        // Upload the cleaned file to GitHub
                        // Upload files directly to the root directory
                        val repoPath = relativePath

                        val fileSuccess = uploadFile(
                            token = token,
                            repoOwnerAndName = repoOwnerAndName,
                            branch = branch,
                            filePath = repoPath,
                            contentBytes = fileBytes,
                            commitMessage = "Updated project file: $relativePath via SketchGit"
                        )
                        if (fileSuccess) {
                            successCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed uploading individual file: $relativePath", e)
                        // Log message and continue with next file to keep upload robust
                        progressListener.onMessage("تنبيه: فشل رفع $relativePath. تابع تخطي لتأمين المزامنة.")
                    }
                }

                progressListener.onMessage("اكتملت مزامنة الملفات. تم رفع $successCount من إجمالي $totalCount ملفات.")
                if (successCount > 0) {
                    try {
                        ensureWorkflowFileExists(token, repoOwnerAndName, branch)
                        progressListener.onMessage("تم إنشاء وتأكيد ملف البناء التلقائي .github/workflows/android-build.yml بنجاح!")
                        ensureBuildRunFileExists(token, repoOwnerAndName, branch)
                        progressListener.onMessage("تم تحديث ملف البناء التلقائي buildrun.txt بنجاح!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to automatically create workflow or trigger build: ${e.localizedMessage}")
                        progressListener.onMessage("ملاحظة: تعذر تكوين ملف البناء التلقائي حالياً: ${e.localizedMessage}")
                    }
                }
                progressListener.onFinished(successCount > 0)
            } catch (e: Exception) {
                progressListener.onError("عطل في المزامنة المتسلسلة: ${e.localizedMessage}")
                progressListener.onFinished(false)
            } finally {
                tempExtractDir?.deleteRecursively()
            }
        }
    }

    private fun gatherUploadFiles(root: File, current: File, list: MutableList<File>) {
        if (current.isDirectory) {
            val name = current.name
            // Skip common temporary bin folders & dot git structures
            if (name == "bin" || name == "gen" || name == "build" || name == ".git" || name == "local.properties") {
                return
            }
            val files = current.listFiles() ?: return
            for (file in files) {
                gatherUploadFiles(root, file, list)
            }
        } else {
            list.add(current)
        }
    }

    /**
     * Fetches all repositories of the authenticated user from GitHub.
     */
    fun getUserRepositories(token: String): List<Map<String, String>> {
        val url = "https://api.github.com/user/repos?sort=updated&per_page=100"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val responseBody = response.body?.string() ?: ""
                val reposArray = org.json.JSONArray(responseBody)
                val repoList = mutableListOf<Map<String, String>>()
                for (i in 0 until reposArray.length()) {
                    val repoJson = reposArray.getJSONObject(i)
                    val name = repoJson.getString("name")
                    val fullName = repoJson.getString("full_name")
                    val description = repoJson.optString("description", "")
                    val isPrivate = repoJson.optBoolean("private", false)
                    repoList.add(mapOf(
                        "name" to name,
                        "fullName" to fullName,
                        "description" to description,
                        "isPrivate" to isPrivate.toString()
                    ))
                }
                return repoList
            } else {
                throw IOException("فشل جلب مستودعات GitHub بكود: ${response.code}")
            }
        }
    }

    /**
     * Lists backup files inside the backups/ directory in a given repository.
     */
    fun listRepoBackups(token: String, repoOwnerAndName: String, branch: String): List<Map<String, String>> {
        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/backups?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val responseBody = response.body?.string() ?: ""
                val filesArray = org.json.JSONArray(responseBody)
                val fileList = mutableListOf<Map<String, String>>()
                for (i in 0 until filesArray.length()) {
                    val fileJson = filesArray.getJSONObject(i)
                    val name = fileJson.getString("name")
                    val path = fileJson.getString("path")
                    val downloadUrl = fileJson.optString("download_url", "")
                    val size = fileJson.optLong("size", 0).toString()
                    fileList.add(mapOf(
                        "name" to name,
                        "path" to path,
                        "downloadUrl" to downloadUrl,
                        "size" to size
                    ))
                }
                return fileList
            } else if (response.code == 404) {
                return emptyList()
            } else {
                throw IOException("فشل جلب ملفات النسخ الاحتياطي بكود: ${response.code}")
            }
        }
    }

    /**
     * Downloads a file from GitHub by direct raw URL and writes it locally.
     */
    fun downloadFile(token: String?, downloadUrl: String, destFile: File) {
        val builder = Request.Builder().url(downloadUrl)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "token $token")
        }
        val request = builder.get().build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val byteStream = response.body?.byteStream() ?: throw IOException("Empty body stream response")
                destFile.outputStream().use { fos ->
                    byteStream.copyTo(fos)
                }
            } else {
                throw IOException("فشل تحميل الملف بكود: ${response.code}")
            }
        }
    }

    /**
     * Searches public or authenticated GitHub repositories matching a query (e.g., "sketchware").
     */
    fun searchRepositories(token: String?, query: String): List<Map<String, String>> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$encodedQuery&sort=stars&order=desc&per_page=30"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "SketchGit")
        
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "token $token")
        }
        
        val request = requestBuilder.get().build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val responseBody = response.body?.string() ?: ""
                val searchResult = org.json.JSONObject(responseBody)
                val itemsArray = searchResult.optJSONArray("items") ?: org.json.JSONArray()
                val repoList = mutableListOf<Map<String, String>>()
                for (i in 0 until itemsArray.length()) {
                    val repoJson = itemsArray.getJSONObject(i)
                    val name = repoJson.getString("name")
                    val fullName = repoJson.getString("full_name")
                    val description = repoJson.optString("description", "")
                    val isPrivate = repoJson.optBoolean("private", false)
                    val ownerJson = repoJson.optJSONObject("owner")
                    val avatarUrl = ownerJson?.optString("avatar_url", "") ?: ""
                    val stars = repoJson.optInt("stargazers_count", 0).toString()
                    val forks = repoJson.optInt("forks_count", 0).toString()
                    val language = repoJson.optString("language", "Kotlin")
                    val htmlUrl = repoJson.optString("html_url", "")
                    val branch = repoJson.optString("default_branch", "main")
                    repoList.add(mapOf(
                        "name" to name,
                        "fullName" to fullName,
                        "description" to description,
                        "isPrivate" to isPrivate.toString(),
                        "avatarUrl" to avatarUrl,
                        "stars" to stars,
                        "forks" to forks,
                        "language" to language,
                        "htmlUrl" to htmlUrl,
                        "defaultBranch" to branch
                    ))
                }
                return repoList
            } else {
                throw IOException("فشل جلب مستودعات GitHub العامة بكود: ${response.code}")
            }
        }
    }

    /**
     * Creates a new GitHub repository for the authenticated user.
     */
    fun createUserRepository(
        token: String,
        name: String,
        description: String,
        isPrivate: Boolean
    ): Boolean {
        val url = "https://api.github.com/user/repos"
        val jsonPayload = org.json.JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", isPrivate)
            put("auto_init", false) // Do not auto-initialize so it's clean for direct source push
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            return response.code == 201
        }
    }

    /**
     * Ensures that the trigger file `buildrun.txt` exists in the repository with the content 'ufufy'.
     * This file change triggers the GitHub automated build process based on push/dispatch events.
     */
    fun ensureBuildRunFileExists(
        token: String,
        repoOwnerAndName: String,
        branch: String
    ): Boolean {
        return try {
            val content = "ufufy"
            uploadFile(
                token = token,
                repoOwnerAndName = repoOwnerAndName,
                branch = branch,
                filePath = "buildrun.txt",
                contentBytes = content.toByteArray(Charsets.UTF_8),
                commitMessage = "Trigger automated build via buildrun.txt"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to automate buildrun.txt update: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Ensures that the Android GitHub Actions workflow file `.github/workflows/android-build.yml` exists in the repository.
     * If not, it commits/uploads it to enable remote automatic packaging.
     */
    @Throws(IOException::class)
    fun ensureWorkflowFileExists(
        token: String,
        repoOwnerAndName: String,
        branch: String
    ): Boolean {
        val path = ".github/workflows/android-build.yml"
        
        // 1. Fetch file info (SHA and content) if it already exists
        val (existingSha, existingContent) = try {
            val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$path?ref=$branch"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val sha = json.optString("sha", null)
                    val contentBase64 = json.optString("content", "")
                    val decoded = if (contentBase64.isNotEmpty()) {
                        String(Base64.decode(contentBase64.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                    } else {
                        ""
                    }
                    Pair(sha, decoded)
                } else {
                    Pair(null, "")
                }
            }
        } catch (e: Exception) {
            Log.e("GitHubService", "Safely ignored existing checks failure: ${e.localizedMessage}")
            Pair(null, "")
        }

        // Workflow content
        val workflowContent = """
            name: Build Android APK
            on:
              workflow_dispatch:
              push:
                branches: [ "$branch" ]
                paths:
                  - 'buildrun.txt'
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up JDK 17
                    uses: actions/setup-java@v4
                    with:
                      java-version: '17'
                      distribution: 'temurin'
                  - name: Setup Gradle
                    uses: gradle/actions/setup-gradle@v4
                    with:
                      gradle-version: 8.11.1
                  - name: Build with Gradle
                    run: |
                      # Dynamic search for Gradle project directory
                      gradle_dir=$(find . -name "settings.gradle" -o -name "settings.gradle.kts" | head -n 1 | xargs dirname)
                      if [ -n "${"$"}{gradle_dir}" ] && [ "${"$"}{gradle_dir}" != "." ]; then
                        echo "Found Gradle project in: ${"$"}{gradle_dir}"
                        cd "${"$"}{gradle_dir}"
                      else
                        echo "No settings.gradle found in subdirectory, running from root"
                      fi
                      
                      # Automatically detect the required Gradle version based on AGP version in the project recursively
                      target_gradle_version="8.11.1"
                      
                      # 1. Automatically detect wrapper version if any properties file exists recursively
                      for prop in $(find . -name "gradle-wrapper.properties" 2>/dev/null); do
                        wrapper_version=$(grep "distributionUrl" "${"$"}{prop}" 2>/dev/null | sed -n 's/.*gradle-\([0-9.]*\)-\(bin\|all\)\.zip.*/\1/p' || true)
                        if [ -n "${"$"}{wrapper_version}" ] ; then
                          echo "Found Gradle version in wrapper properties (${"$"}{prop}): ${"$"}{wrapper_version}"
                          target_gradle_version="${"$"}{wrapper_version}"
                          break
                        fi
                      done
                      
                      # 2. Extract AGP version recursively to handle newer Gradle requirements (AGP 8.12+/9.1+ requires higher Gradle version)
                      agp_version=""
                      # Try Version catalogs first (libs.versions.toml) recursively
                      for toml in $(find . -name "libs.versions.toml" 2>/dev/null); do
                        version=$(grep -E "^\s*agp\s*=\s*[\"']?[0-9.]+[\"']?" "${"$"}{toml}" 2>/dev/null | grep -oE "[0-9.]+" || true)
                        if [ -n "${"$"}{version}" ]; then
                          echo "Found AGP version in version catalog (${"$"}{toml}): ${"$"}{version}"
                          agp_version="${"$"}{version}"
                          break
                        fi
                      done
                      
                      # Try build.gradle and build.gradle.kts files recursively
                      if [ -z "${"$"}{agp_version}" ] ; then
                        for f in $(find . -name "build.gradle" -o -name "build.gradle.kts" 2>/dev/null); do
                          # Try classpaths first
                          version=$(grep -oE "com\.android\.tools\.build:gradle:[0-9.]+" "${"$"}{f}" 2>/dev/null | cut -d':' -f4 || true)
                          if [ -z "${"$"}{version}" ]; then
                            version=$(grep -oE "com\.android\.tools\.build:gradle:[0-9.]+" "${"$"}{f}" 2>/dev/null | cut -d':' -f3 || true)
                          fi
                          # Try plugins versions block
                          if [ -z "${"$"}{version}" ]; then
                            version=$(grep -oE "com\.android\.(application|library)[\"']? version [\"']?[0-9.]+" "${"$"}{f}" 2>/dev/null | grep -oE "[0-9.]+" | tail -n 1 || true)
                          fi
                          if [ -z "${"$"}{version}" ]; then
                            version=$(grep -oE "id\([\"']com\.android\.(application|library)[\"']\)\s*version\s*[\"']([0-9.]+)[\"']" "${"$"}{f}" 2>/dev/null | grep -oE "[0-9.]+" | tail -n 1 || true)
                          fi
                          if [ -n "${"$"}{version}" ]; then
                            echo "Found AGP version in build file (${"$"}{f}): ${"$"}{version}"
                            agp_version="${"$"}{version}"
                            break
                          fi
                        done
                      fi
                      
                      if [ -n "${"$"}{agp_version}" ] ; then
                        echo "Detected Android Gradle Plugin (AGP) version: ${"$"}{agp_version}"
                        major_agp=$(echo "${"$"}{agp_version}" | cut -d'.' -f1)
                        minor_agp=$(echo "${"$"}{agp_version}" | cut -d'.' -f2)
                        
                        if [ "${"$"}{major_agp}" -ge 9 ] ; then
                          if [ "${"$"}{minor_agp}" -ge 1 ] ; then
                            target_gradle_version="9.3.1"
                          else
                            target_gradle_version="9.0"
                          fi
                        elif [ "${"$"}{major_agp}" -eq 8 ] ; then
                          if [ "${"$"}{minor_agp}" -ge 8 ] ; then
                            target_gradle_version="9.0"
                          elif [ "${"$"}{minor_agp}" -ge 7 ] ; then
                            target_gradle_version="8.9"
                          elif [ "${"$"}{minor_agp}" -ge 5 ] || [ "${"$"}{minor_agp}" -eq 6 ] ; then
                            target_gradle_version="8.7"
                          elif [ "${"$"}{minor_agp}" -ge 4 ] ; then
                            target_gradle_version="8.6"
                          elif [ "${"$"}{minor_agp}" -ge 3 ] ; then
                            target_gradle_version="8.4"
                          elif [ "${"$"}{minor_agp}" -ge 2 ] ; then
                            target_gradle_version="8.2"
                          else
                            target_gradle_version="8.0"
                          fi
                        fi
                      fi
                      
                      echo "Determined target Gradle version to use: ${"$"}{target_gradle_version}"

                      # Ensure android.useAndroidX=true and android.enableJetifier=true are set in gradle.properties
                      if [ ! -f gradle.properties ]; then
                        touch gradle.properties
                      fi
                      if ! grep -q "android.useAndroidX" gradle.properties; then
                        echo "" >> gradle.properties
                        echo "android.useAndroidX=true" >> gradle.properties
                        echo "android.enableJetifier=true" >> gradle.properties
                      else
                        sed -i 's/android.useAndroidX=false/android.useAndroidX=true/g' gradle.properties
                      fi
                      if ! grep -q "android.enableJetifier" gradle.properties; then
                        echo "android.enableJetifier=true" >> gradle.properties
                      fi
                      
                      # Automatically generate or update Gradle wrapper to match target_gradle_version
                      generate_wrapper=false
                      if [ ! -f gradlew ]; then
                        echo "Gradle wrapper script (gradlew) not found."
                        generate_wrapper=true
                      elif [ -f gradle/wrapper/gradle-wrapper.properties ]; then
                        current_wrapper_version=$(grep "distributionUrl" gradle/wrapper/gradle-wrapper.properties 2>/dev/null | sed -n 's/.*gradle-\([0-9.]*\)-\(bin\|all\)\.zip.*/\1/p' || true)
                        if [ "${"$"}{current_wrapper_version}" != "${"$"}{target_gradle_version}" ]; then
                          echo "Gradle wrapper version mismatch: current is ${"$"}{current_wrapper_version}, target is ${"$"}{target_gradle_version}."
                          generate_wrapper=true
                        fi
                      else
                        generate_wrapper=true
                      fi
                      
                      if [ "${"$"}{generate_wrapper}" = true ]; then
                        echo "Generating wrapper via global Gradle with version ${"$"}{target_gradle_version}..."
                        gradle wrapper --gradle-version "${"$"}{target_gradle_version}"
                      fi
                      
                      # Deduplicate XML resource files to prevent duplicate resource errors
                      echo "Running Python-based resource deduplicator..."
                      python3 -c "import sys, textwrap; exec(textwrap.dedent(sys.stdin.read()))" << 'EOF'
                      import os
                      import xml.etree.ElementTree as ET

                      res_values_dirs = []
                      for root, dirs, files in os.walk('.'):
                          normalized_root = root.replace('\\\\', '/')
                          if 'res/values' in normalized_root:
                              res_values_dirs.append(root)

                      for res_dir in res_values_dirs:
                          print(f'Cleaning duplicates in: {res_dir}')
                          priorities = {
                              'color': 'colors.xml',
                              'string': 'strings.xml',
                              'style': 'themes.xml',
                              'dimen': 'dimens.xml'
                          }
                          
                          files_data = {}
                          if os.path.exists(res_dir):
                              xml_files = [f for f in os.listdir(res_dir) if f.endswith('.xml')]
                          else:
                              xml_files = []
                              
                          for f in xml_files:
                              path = os.path.join(res_dir, f)
                              try:
                                  tree = ET.parse(path)
                                  files_data[f] = (tree, tree.getroot())
                              except Exception as e:
                                  print(f'Error parsing {f}: {e}')
                                  
                          seen = {}
                          
                          # Pass 1: Register prioritized items
                          for f, (tree, root_elem) in files_data.items():
                              for child in list(root_elem):
                                  name = child.attrib.get('name')
                                  if not name:
                                      continue
                                  tag = child.tag
                                  priority_file = priorities.get(tag)
                                  if priority_file == f:
                                      seen[(tag, name)] = f
                                      
                          # Pass 2: Remove duplicates of registered prioritize items and others
                          for f, (tree, root_elem) in list(files_data.items()):
                              modified = False
                              for child in list(root_elem):
                                  name = child.attrib.get('name')
                                  if not name:
                                      continue
                                  tag = child.tag
                                  key = (tag, name)
                                  
                                  if key in seen:
                                      if seen[key] != f:
                                          print(f'Removing duplicate {tag} \"{name}\" from {f} (already exists in {seen[key]})')
                                          root_elem.remove(child)
                                          modified = True
                                  else:
                                      seen[key] = f
                                      
                              if modified:
                                  try:
                                      tree.write(os.path.join(res_dir, f), encoding='utf-8', xml_declaration=True)
                                  except Exception as e:
                                      print(f'Failed to write {f}: {e}')
                      EOF
                      
                      chmod +x gradlew || true
                      ./gradlew assembleDebug
                      
                      # Move back to repository root to search and copy all APKs
                      cd "${"$"}{GITHUB_WORKSPACE}"
                      mkdir -p apk-artifacts
                      find . -name "*.apk" -type f -exec cp {} apk-artifacts/ \;
                      echo "Copied APK files to root apk-artifacts directory:"
                      ls -la apk-artifacts/ || true
                  - name: Upload APK
                    uses: actions/upload-artifact@v4
                    with:
                      name: MhasbMali-Debug-APK
                      path: apk-artifacts/*.apk
         """.trimIndent()

        // Normalize and clean up both content strings to check if they are identical
        val normalizedWorkflow = workflowContent.trim().replace("\r\n", "\n")
        val normalizedExisting = existingContent.trim().replace("\r\n", "\n")
        if (existingSha != null && normalizedExisting == normalizedWorkflow) {
            // Yes, correct version is already in place!
            return true
        }

        val base64Content = Base64.encodeToString(workflowContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val jsonPayload = JSONObject().apply {
            put("message", "Add SketchGit Android Compilation Actions Workflow ⚙️ [Skip CI]")
            put("content", base64Content)
            put("branch", branch)
            if (existingSha != null) {
                put("sha", existingSha)
            }
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$path"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 201 || response.code == 200) {
                return true
            } else {
                val errorBody = response.body?.string() ?: ""
                throw IOException("فشل إنشاء ملف البناء على GitHub بكود: ${response.code}. رسالة الاستجابة: $errorBody")
            }
        }
    }

    /**
     * Triggers the GitHub Actions Android rebuild workflow dispatch event.
     * Returns a Pair: first element is success state, second is status message or runUrl.
     */
    fun triggerGitHubWorkflow(
        token: String,
        repoOwnerAndName: String,
        branch: String
    ): Pair<Boolean, String> {
        val url = "https://api.github.com/repos/$repoOwnerAndName/actions/workflows/android-build.yml/dispatches"
        val jsonPayload = JSONObject().apply {
            put("ref", branch)
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 204 || response.code == 200 || response.code == 202) {
                    val runPageUrl = "https://github.com/$repoOwnerAndName/actions"
                    return Pair(true, runPageUrl)
                } else if (response.code == 404) {
                    return Pair(false, "لم يتم العثور على ملف Workflow. يرجى المزامنة أولاً.")
                } else {
                    val errBody = response.body?.string() ?: ""
                    return Pair(false, "فشل تفعيل البناء: كود الاستجابة ${response.code}\n$errBody")
                }
            }
        } catch (e: Exception) {
            return Pair(false, "حدث خطأ أثناء محاولة الاتصال بـ GitHub Actions: ${e.localizedMessage}")
        }
    }
}
