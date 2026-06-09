package com.example.service

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.regex.Pattern

object SketchwareUtils {
    private const val TAG = "SketchwareUtils"

    /**
     * Scans a root directory (e.g. /sdcard/.sketchware/data/) for Sketchware projects.
     * Returns a list of parsed project data maps. Supports folded structures and zipped archives.
     */
    fun scanLocalSketchwareProjects(dataDir: File): List<Map<String, String>> {
        val projects = mutableListOf<Map<String, String>>()
        if (!dataDir.exists()) {
            dataDir.mkdirs() // Robust: auto-create if missing
        }
        if (!dataDir.isDirectory) {
            Log.d(TAG, "Data directory does not exist or notice directory: ${dataDir.absolutePath}")
            return projects
        }

        // 1. Scan folders
        val folders = dataDir.listFiles { file -> file.isDirectory }
        if (folders != null) {
            for (folder in folders) {
                val projectId = folder.name
                // Sketchware projects have a metadata file named 'project' inside the data folder
                val projectMetaFile = File(folder, "project")
                if (projectMetaFile.exists()) {
                    try {
                        val content = projectMetaFile.readText(Charsets.UTF_8).trim()
                        val parsed = parseSketchwareProjectJson(content, projectId, folder.absolutePath)
                        if (parsed != null) {
                            projects.add(parsed)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading project metadata for folder $projectId", e)
                    }
                }
            }
        }

        // 2. Scan ZIP compressed archives inside dataDir
        val files = dataDir.listFiles { file -> file.isFile && file.name.endsWith(".zip", ignoreCase = true) }
        if (files != null) {
            for (zipFile in files) {
                val parsed = parseProjectMetadataFromZip(zipFile)
                if (parsed != null) {
                    projects.add(parsed)
                }
            }
        }

        return projects
    }

    /**
     * Inspects a ZIP archive on the fly, searching for Sketchware 'project' JSON config.
     * Extracts details or falls back to name of archive.
     */
    fun parseProjectMetadataFromZip(zipFile: File): Map<String, String>? {
        if (!zipFile.exists() || !zipFile.name.endsWith(".zip", ignoreCase = true)) return null
        
        // Remove .zip extension safely
        val fileName = zipFile.name
        val cleanName = if (fileName.length > 4) fileName.substring(0, fileName.length - 4) else fileName
        val projectId = cleanName.replace("[^a-zA-Z0-9]".toRegex(), "")
        
        var name = cleanName
        var packageName = "com.sketchware.${name.lowercase().replace("[^a-zA-Z0-9]".toRegex(), "")}"
        var versionName = "1.0"

        try {
            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (entryName.endsWith("project", ignoreCase = true)) {
                        val content = zipIn.reader(Charsets.UTF_8).readText()
                        val id = extractValueFromJson(content, "sc_id") ?: projectId
                        val extractedName = extractValueFromJson(content, "sc_name") ?: name
                        val extractedPkg = extractValueFromJson(content, "my_sc_pkg_name") ?: packageName
                        val extractedVer = extractValueFromJson(content, "sc_ver_name") ?: versionName
                        return mapOf(
                            "id" to id,
                            "name" to extractedName,
                            "packageName" to extractedPkg,
                            "versionName" to extractedVer,
                            "localPath" to zipFile.absolutePath
                        )
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning zipped project metadata: ${zipFile.name}", e)
        }

        return mapOf(
            "id" to projectId,
            "name" to name,
            "packageName" to packageName,
            "versionName" to versionName,
            "localPath" to zipFile.absolutePath
        )
    }

    /**
     * Parses the Sketchware 'project' config JSON.
     * Since GSON / Moshi might fail if the JSON is formatted differently, we will parse with simple regex/string
     * extraction, which is extremely robust and does not rely on rigid schemas.
     */
    private fun parseSketchwareProjectJson(json: String, folderId: String, absolutePath: String): Map<String, String>? {
        try {
            // Typical Sketchware 'project' file keys:
            // "sc_id": "601"
            // "my_sc_pkg_name": "com.my.app"
            // "sc_name": "My App"
            // "sc_ver_name": "1.0"
            val id = extractValueFromJson(json, "sc_id") ?: folderId
            val name = extractValueFromJson(json, "sc_name") ?: "Unnamed Proj $folderId"
            val packageName = extractValueFromJson(json, "my_sc_pkg_name") ?: "com.my.project$folderId"
            val versionName = extractValueFromJson(json, "sc_ver_name") ?: "1.0"

            return mapOf(
                "id" to id,
                "name" to name,
                "packageName" to packageName,
                "versionName" to versionName,
                "localPath" to absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error for json", e)
            return null
        }
    }

    private fun extractValueFromJson(json: String, key: String): String? {
        val patternStr = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val pattern = Pattern.compile(patternStr)
        val matcher = pattern.matcher(json)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            // Check for unquoted/int values
            val intPatternStr = "\"$key\"\\s*:\\s*(\\d+)"
            val intMatcher = Pattern.compile(intPatternStr).matcher(json)
            if (intMatcher.find()) intMatcher.group(1) else null
        }
    }

    /**
     * Parses an AndroidManifest.xml string and cleans it:
     * - Removes duplicate uses-permission tags.
     * - Formats it and resolves typical XML issues.
     */
    fun fixAndroidManifest(manifestContent: String): String {
        try {
            val lines = manifestContent.lines()
            val cleanLines = mutableListOf<String>()
            val foundPermissions = mutableSetOf<String>()

            // Extract permission names and strip duplicates
            // Pattern for: <uses-permission android:name="android.permission.INTERNET" />
            val permissionPattern = Pattern.compile("<uses-permission\\s+[^>]*android:name\\s*=\\s*\"([^\"]+)\"[^>]*>")

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("<uses-permission")) {
                    val matcher = permissionPattern.matcher(trimmed)
                    if (matcher.find()) {
                        val permissionName = matcher.group(1) ?: ""
                        if (foundPermissions.contains(permissionName)) {
                            // Already included, skip duplicate permission tag!
                            continue
                        } else {
                            foundPermissions.add(permissionName)
                            cleanLines.add(line) // Add original formatted line
                        }
                    } else {
                        // Backup match fallback if formatting is weird
                        cleanLines.add(line)
                    }
                } else {
                    cleanLines.add(line)
                }
            }
            return cleanLines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed cleaning AndroidManifest", e)
            return manifestContent // Return original if crashed
        }
    }

    /**
     * Parses a colors.xml string and cleans duplicate colors:
     * - Resolves duplicate <color name="xyz"> elements by retaining the first occurrence.
     * - Normalizes hex-codes (ensuring leading # and formatting).
     */
    fun fixColorsXml(colorsContent: String): String {
        try {
            val lines = colorsContent.lines()
            val cleanLines = mutableListOf<String>()
            val foundColorNames = mutableSetOf<String>()

            // Pattern to match: <color name="colorPrimary">#FFFFFF</color>
            // or multiline color structures
            val colorPattern = Pattern.compile("<color\\s+[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*>([^<]*)</color>")

            var insideResources = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("<resources>")) {
                    insideResources = true
                    cleanLines.add(line)
                    continue
                }
                if (trimmed.contains("</resources>")) {
                    insideResources = false
                    cleanLines.add(line)
                    continue
                }

                if (trimmed.startsWith("<color") && trimmed.endsWith("</color>")) {
                    val matcher = colorPattern.matcher(trimmed)
                    if (matcher.find()) {
                        val colorName = matcher.group(1) ?: ""
                        var colorVal = matcher.group(2)?.trim() ?: ""

                        if (foundColorNames.contains(colorName)) {
                            // Skip duplicates!
                            continue
                        }

                        foundColorNames.add(colorName)

                        // Normalize Color HEX-Codes
                        if (!colorVal.startsWith("#")) {
                            colorVal = "#$colorVal"
                        }
                        // Validate format length (should be #RGB, #ARGB, #RRGGBB, #AARRGGBB)
                        val length = colorVal.length
                        if (length != 4 && length != 5 && length != 7 && length != 9) {
                            // If invalid length, default to solid dark/white or keep it
                            Log.w(TAG, "Invalid color hex code format: $colorVal, name: $colorName")
                        }

                        // Reassemble normalized color tag
                        val leadingWhitespace = line.substring(0, line.indexOf("<color"))
                        cleanLines.add("$leadingWhitespace<color name=\"$colorName\">$colorVal</color>")
                    } else {
                        cleanLines.add(line)
                    }
                } else {
                    cleanLines.add(line)
                }
            }
            return cleanLines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed cleaning colors.xml", e)
            return colorsContent
        }
    }

    /**
     * Compresses the files in a source directory to a ZIP archive File.
     * While scanning, it optionally applies xml optimizations for Manifest and Colors files in memory,
     * so that the resulting uploaded Backup ZIP is fully functional, cleaned, and pristine!
     */
    fun compressFolderToZip(
        sourceDir: File,
        destZipFile: File,
        applyFixes: Boolean = true
    ): Boolean {
        if (!sourceDir.exists()) return false
        ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
            zipFileOrFolder(sourceDir, sourceDir, zipOut, applyFixes)
        }
        return true
    }

    private fun zipFileOrFolder(
        root: File,
        currentFile: File,
        zipOut: ZipOutputStream,
        applyFixes: Boolean
    ) {
        if (currentFile.isDirectory) {
            // Skip typical build bins or cached artifacts that don't need backup
            val name = currentFile.name
            if (name == "bin" || name == "gen" || name == "build" || name == ".git") {
                return
            }

            val files = currentFile.listFiles() ?: return
            for (file in files) {
                zipFileOrFolder(root, file, zipOut, applyFixes)
            }
        } else {
            val relativePath = currentFile.relativeTo(root).path
            val zipEntry = ZipEntry(relativePath)
            zipOut.putNextEntry(zipEntry)

            try {
                val fileName = currentFile.name
                if (applyFixes && (fileName == "AndroidManifest.xml" || fileName == "colors.xml")) {
                    val rawContent = currentFile.readText(Charsets.UTF_8)
                    val processed = if (fileName == "AndroidManifest.xml") {
                        fixAndroidManifest(rawContent)
                    } else {
                        fixColorsXml(rawContent)
                    }
                    zipOut.write(processed.toByteArray(Charsets.UTF_8))
                } else {
                    // Regular copy stream safely
                    FileInputStream(currentFile).use { fileIn ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                            zipOut.write(buffer, 0, bytesRead)
                        }
                    }
                }
            } finally {
                zipOut.closeEntry()
            }
        }
    }

    /**
     * Unzips a ZIP archive into a target destination folder.
     */
    fun decompressZipToFolder(zipFile: File, destFolder: File): Boolean {
        try {
            if (!zipFile.exists()) return false
            if (!destFolder.exists()) {
                destFolder.mkdirs()
            }
            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val filePath = File(destFolder, entry.name)
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        // Ensure parent folder exists
                        filePath.parentFile?.mkdirs()
                        java.io.FileOutputStream(filePath).use { fos ->
                            val buffer = ByteArray(4096)
                            var len: Int
                            while (zipIn.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting ZIP file to: ${destFolder.absolutePath}", e)
            return false
        }
    }
}
