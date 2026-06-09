package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.GitHubConfig
import com.example.data.database.SketchwareProject
import com.example.data.database.SyncLog
import com.example.ui.viewmodel.SketchGitViewModel
import com.example.ui.viewmodel.SyncProgressState
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SketchGitViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentConfig by viewModel.gitHubConfig.collectAsStateWithLifecycle()
    val projectsList by viewModel.allProjects.collectAsStateWithLifecycle()
    val syncLogsList by viewModel.syncLogs.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val scanPath by viewModel.scanPath.collectAsStateWithLifecycle()

    val githubRepos by viewModel.githubRepos.collectAsStateWithLifecycle()
    val isFetchingRepos by viewModel.isFetchingRepos.collectAsStateWithLifecycle()
    val repoFetchError by viewModel.repoFetchError.collectAsStateWithLifecycle()

    val githubBackups by viewModel.githubBackups.collectAsStateWithLifecycle()
    val isFetchingBackups by viewModel.isFetchingBackups.collectAsStateWithLifecycle()
    val backupFetchError by viewModel.backupFetchError.collectAsStateWithLifecycle()

    val projectConflicts by viewModel.projectConflicts.collectAsStateWithLifecycle()
    val githubBuildState by viewModel.githubBuildState.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Render build progress dialog overlay
    val buildState by viewModel.buildState.collectAsStateWithLifecycle()
    BuildProgressDialog(
        buildState = buildState,
        onDismiss = { viewModel.resetBuildState() }
    )

    // Render remote GitHub Actions run dialog overlay
    GitHubBuildProgressDialog(
        buildState = githubBuildState,
        onDismiss = { viewModel.resetGithubBuildState() }
    )

    // Check for storage permission
    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true // Fallback handled by runtime permissions
            }
        )
    }

    // Periodically re-check permission when resuming or clicking
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasStoragePermission = Environment.isExternalStorageManager()
        }
    }

    val editorActiveProject by viewModel.editorActiveProject.collectAsStateWithLifecycle()
    val editorWorkingRoot by viewModel.editorWorkingRoot.collectAsStateWithLifecycle()
    val editorFiles by viewModel.editorFiles.collectAsStateWithLifecycle()
    val editorSelectedFile by viewModel.editorSelectedFile.collectAsStateWithLifecycle()
    val editorFileContent by viewModel.editorFileContent.collectAsStateWithLifecycle()
    val editorMessage by viewModel.editorMessage.collectAsStateWithLifecycle()

    val isCreatingRepo by viewModel.isCreatingRepo.collectAsStateWithLifecycle()
    val repoCreationResult by viewModel.repoCreationResult.collectAsStateWithLifecycle()

    if (editorActiveProject != null) {
        ProjectEditorLayout(
            project = editorActiveProject!!,
            workingRoot = editorWorkingRoot,
            filesList = editorFiles,
            selectedFile = editorSelectedFile,
            fileContent = editorFileContent,
            editorMessage = editorMessage,
            onClose = { viewModel.closeProjectEditor() },
            onSelectFile = { viewModel.selectFileForEditing(it) },
            onSaveFile = { file, content -> viewModel.saveActiveFile(editorActiveProject!!, file, content, context) },
            onDeleteFile = { file -> viewModel.deleteEditorFile(editorActiveProject!!, file, context) },
            onCreateFileOrFolder = { parentDir, name, isFolder ->
                viewModel.createEditorFileOrFolder(editorActiveProject!!, parentDir, name, isFolder, context)
            },
            onBuildProject = { viewModel.buildProjectApk(editorActiveProject!!, context) },
            onClearMessage = { viewModel.clearEditorMessage() }
        )
    } else {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SketchGit",
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "مزامنة ورفع مشاريع Sketchware إلى GitHub",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    Image(
                        painter = painterResource(id = R.drawable.img_app_logo),
                        contentDescription = "SketchGit Logo",
                        modifier = Modifier
                            .padding(start = 12.dp, end = 8.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val pm = context.packageManager
                                val appInfo = pm.getApplicationInfo(context.packageName, 0)
                                val apkFile = File(appInfo.sourceDir)
                                val cacheApkFile = File(context.cacheDir, "SketchGit.apk")
                                apkFile.inputStream().use { input ->
                                    cacheApkFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    cacheApkFile
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/vnd.android.package-archive"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "شارك تطبيق SketchGit APK"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "فشل مشاركة التطبيق: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(19.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "مشاركة التطبيق APK",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (activeTab == 0) {
                        IconButton(
                            onClick = { viewModel.scanProjects() },
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(38.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(19.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تحديث الفحص",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("لوحة التحكم") }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("المشاريع") }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("الإعدادات") }
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("السجلات") }
                )
                NavigationBarItem(
                    selected = activeTab == 4,
                    onClick = { activeTab = 4 },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    label = { Text("مشاريع GitHub") }
                )
            }
        },
        floatingActionButton = {
            if (activeTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("إضافة مسار مخصص") },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Permission Alert Banner if missing on modern SDK
            if (!hasStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "صلاحية الوصول إلى الملفات مطلوبة",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "يحتاج التطبيق لصلاحية الوصول إلى كافة الملفات ليتمكن من قراءة ملفات .sketchware المشفرة ونسخها احتياطياً بشكل صحيح.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                }
                                // Recheck in a bit
                                hasStoragePermission = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("منح الصلاحية الآن / Grant Access")
                        }
                    }
                }
            }

            // Tabs Content
            when (activeTab) {
                0 -> DashboardTabContent(
                    projects = projectsList,
                    config = currentConfig,
                    projectConflicts = projectConflicts,
                    onTriggerRemoteBuild = { viewModel.triggerRemoteGitHubBuild(it) },
                    onResolveConflicts = { project, keepLocal -> viewModel.resolveConflictsForProject(project, keepLocal) },
                    onQuickSync = { viewModel.syncProject(it) },
                    onOpenEditor = { viewModel.openProjectInEditor(it, context) },
                    onBuildApk = { viewModel.buildProjectApk(it, context) },
                    onScanClick = { viewModel.scanProjects(); viewModel.scanAllProjectsForConflicts() }
                )
                1 -> ProjectsTabContent(
                    projects = projectsList,
                    config = currentConfig,
                    scanPath = scanPath,
                    githubBackups = githubBackups,
                    isFetchingBackups = isFetchingBackups,
                    backupFetchError = backupFetchError,
                    githubRepos = githubRepos,
                    isFetchingRepos = isFetchingRepos,
                    onFetchRepos = { tok -> viewModel.fetchUserRepos(tok) },
                    onFetchBackups = { tok, rep, br -> viewModel.fetchRepoBackups(tok, rep, br) },
                    onDownloadBackup = { tok, downloadUrl, fileName -> viewModel.downloadAndRestoreBackup(tok, downloadUrl, fileName, scanPath) },
                    onDownloadFullRepo = { tok, repo, br -> viewModel.downloadAndRestoreZipball(tok, repo, br, scanPath) },
                    onUpdatePath = { viewModel.updateScanPath(it) },
                    onScanClick = { viewModel.scanProjects() },
                    onSyncItem = { viewModel.syncProject(it) },
                    onOpenEditor = { viewModel.openProjectInEditor(it, context) },
                    onBuildApk = { viewModel.buildProjectApk(it, context) },
                    onDelete = { viewModel.deleteProject(it) },
                    onToggleFixColors = { project, enabled ->
                        viewModel.updateProjectSettings(project.copy(fixColorsOnSync = enabled))
                    },
                    onToggleFixManifest = { project, enabled ->
                        viewModel.updateProjectSettings(project.copy(fixManifestOnSync = enabled))
                    }
                )
                2 -> SettingsTabContent(
                    config = currentConfig,
                    repos = githubRepos,
                    isFetchingRepos = isFetchingRepos,
                    repoFetchError = repoFetchError,
                    onFetchRepos = { viewModel.fetchUserRepos(it) },
                    isCreatingRepo = isCreatingRepo,
                    repoCreationResult = repoCreationResult,
                    onCreateRepo = { name, desc, priv, tok ->
                        viewModel.createRepositoryOnGitHub(tok, name, desc, priv) {
                            viewModel.fetchUserRepos(tok)
                        }
                    },
                    onClearRepoCreationResult = { viewModel.clearRepoCreationResult() },
                    onSave = { user, token, repo, branch, mode, isAuto ->
                        viewModel.saveGitHubConfig(user, token, repo, branch, mode, isAuto)
                    }
                )
                3 -> LogsTabContent(
                    logs = syncLogsList,
                    onClear = { viewModel.clearLogs() }
                )
                4 -> GithubProjectsTabContent(
                    viewModel = viewModel,
                    config = currentConfig,
                    scanPath = scanPath,
                    onImportFullRepo = { tok, repo, br ->
                        viewModel.downloadAndRestoreZipball(tok, repo, br, scanPath)
                    }
                )
            }
        }

        // Add Project Manually Dialog
        if (showAddDialog) {
            AddProjectDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { id, name, pkg, ver, path, fixCol, fixMan ->
                    viewModel.addProjectManually(id, name, pkg, ver, path, fixCol, fixMan)
                    showAddDialog = false
                }
            )
        }

        // Active Sync Overlay Dialog modal
        when (val state = syncState) {
            is SyncProgressState.Idle -> { /* Nothing to show */ }
            is SyncProgressState.Running -> {
                Dialog(onDismissRequest = { /* Modal, non-cancelable */ }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(54.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 5.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "جاري المزامنة مع مستودع GitHub...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.lastMessage,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            if (state.total > 0) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { state.current.toFloat() / state.total },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "ملف: ${state.fileName}",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            is SyncProgressState.Success -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetSyncState() },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(48.dp)) },
                    title = { Text("تمت العملية بنجاح 🎉") },
                    text = { Text(state.message) },
                    confirmButton = {
                        Button(onClick = { viewModel.resetSyncState() }) {
                            Text("رائع")
                        }
                    }
                )
            }
            is SyncProgressState.Failure -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetSyncState() },
                    icon = { Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp)) },
                    title = { Text("خطأ في المزامنة 😅") },
                    text = { Text(state.error) },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.resetSyncState() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("رجوع")
                        }
                    }
                )
            }
        }
    }
    }
}

// ========================== PROJECTS TAB ==========================
@Composable
fun ProjectsTabContent(
    projects: List<SketchwareProject>,
    config: GitHubConfig?,
    scanPath: String,
    githubBackups: List<Map<String, String>>,
    isFetchingBackups: Boolean,
    backupFetchError: String?,
    githubRepos: List<Map<String, String>>,
    isFetchingRepos: Boolean,
    onFetchRepos: (String) -> Unit,
    onFetchBackups: (String, String, String) -> Unit,
    onDownloadBackup: (String, String, String) -> Unit,
    onDownloadFullRepo: (String, String, String) -> Unit,
    onUpdatePath: (String) -> Unit,
    onScanClick: () -> Unit,
    onSyncItem: (String) -> Unit,
    onOpenEditor: (SketchwareProject) -> Unit,
    onBuildApk: (SketchwareProject) -> Unit,
    onDelete: (SketchwareProject) -> Unit,
    onToggleFixColors: (SketchwareProject, Boolean) -> Unit,
    onToggleFixManifest: (SketchwareProject, Boolean) -> Unit
) {
    val context = LocalContext.current
    var expandSearchSet by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Upper search configuration card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مسار فحص مشاريع Sketchware",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    IconButton(onClick = { expandSearchSet = !expandSearchSet }) {
                        Icon(
                            imageVector = if (expandSearchSet) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                }

                if (expandSearchSet) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = scanPath,
                        onValueChange = onUpdatePath,
                        label = { Text("مجلد البيانات لسكيتشوير") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        trailingIcon = {
                            IconButton(onClick = onScanClick) {
                                Icon(Icons.Default.Search, contentDescription = "فحص مجدد")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "المسار الافتراضي للمحاكي/الهاتف هو عادة:\n/storage/emulated/0/.sketchware/data",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onScanClick,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تحديث الفحص", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    val tok = config?.token ?: ""
                    val rep = config?.repositoryName ?: ""
                    val br = config?.branchName ?: "main"
                    if (tok.isNotBlank() && rep.isNotBlank()) {
                        onFetchBackups(tok, rep, br)
                        showImportDialog = true
                    } else {
                        android.widget.Toast.makeText(context, "الرجاء ضبط إعدادات ورمز الوصول لمستودع GitHub في الإعدادات أولاً", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تحميل من GitHub", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showImportDialog) {
            val tok = config?.token ?: ""
            GithubImportProjectsDialog(
                token = tok,
                configuredRepo = config?.repositoryName ?: "",
                configuredBranch = config?.branchName ?: "main",
                repos = githubRepos,
                isFetchingRepos = isFetchingRepos,
                backups = githubBackups,
                isFetchingBackups = isFetchingBackups,
                backupFetchError = backupFetchError,
                onDismiss = { showImportDialog = false },
                onFetchRepos = { onFetchRepos(tok) },
                onFetchBackups = { repo, br -> onFetchBackups(tok, repo, br) },
                onImportBackup = { downloadUrl, name ->
                    onDownloadBackup(tok, downloadUrl, name)
                    showImportDialog = false
                },
                onImportFullRepo = { repo, br ->
                    onDownloadFullRepo(tok, repo, br)
                    showImportDialog = false
                }
            )
        }

        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = R.drawable.img_app_logo),
                        contentDescription = "SketchGit Logo Indicator",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "لا توجد مشاريع نشطة مضافة بعد 📁",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "انقر على تحديث الفحص أو أضف مشروع يدوي بالأسفل.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCardItem(
                        project = project,
                        isConfigured = config != null && config.token.isNotBlank(),
                        onSync = { onSyncItem(project.id) },
                        onOpenEditor = { onOpenEditor(project) },
                        onBuildApk = { onBuildApk(project) },
                        onDelete = { onDelete(project) },
                        onToggleFixColors = { onToggleFixColors(project, it) },
                        onToggleFixManifest = { onToggleFixManifest(project, it) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProjectCardItem(
    project: SketchwareProject,
    isConfigured: Boolean,
    onSync: () -> Unit,
    onOpenEditor: () -> Unit,
    onBuildApk: () -> Unit,
    onDelete: () -> Unit,
    onToggleFixColors: (Boolean) -> Unit,
    onToggleFixManifest: (Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = project.id,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = project.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Sync status chip
                val (statusText, statusColor) = when (project.lastSyncStatus) {
                    "SUCCESS" -> "مكتمل 🟢" to Color(0xFF4CAF50)
                    "FAILED" -> "فشل 🔴" to MaterialTheme.colorScheme.error
                    else -> "غير متزامن" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }

                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (project.lastSyncStatus == "SUCCESS") Color.White else statusColor,
                    modifier = Modifier
                        .background(
                            if (project.lastSyncStatus == "SUCCESS") Color(0xFF4CAF50) else statusColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(100.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expandable options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "آخر مزامنة: " + if (project.lastSyncTimestamp > 0L) {
                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(project.lastSyncTimestamp))
                    } else "مطلقاً",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row {
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.SettingsApplications else Icons.Default.Settings,
                            contentDescription = "خيارات إضافية"
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف المشروع",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text("إعدادات المزامنة الخاصة بالمشروع:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleFixColors(!project.fixColorsOnSync) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("إزالة مكرر الألوان تلقائياً (colors.xml)", fontSize = 12.sp)
                        Checkbox(
                            checked = project.fixColorsOnSync,
                            onCheckedChange = { onToggleFixColors(it) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleFixManifest(!project.fixManifestOnSync) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("إصلاح مكرر صلاحيات (AndroidManifest)", fontSize = 12.sp)
                        Checkbox(
                            checked = project.fixManifestOnSync,
                            onCheckedChange = { onToggleFixManifest(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "المسار المحلي: ${project.localPath}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSync,
                    enabled = isConfigured,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (project.lastSyncStatus == "SUCCESS") Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConfigured) "مزامنة (Push)" else "المزامنة",
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }

                Button(
                    onClick = onOpenEditor,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "محرر الأكواد 📝",
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }

                Button(
                    onClick = onBuildApk,
                    modifier = Modifier
                        .weight(1.0f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تجميع APK ⚙️",
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

// ========================== SETTINGS TAB ==========================
@Composable
fun SettingsTabContent(
    config: GitHubConfig?,
    repos: List<Map<String, String>>,
    isFetchingRepos: Boolean,
    repoFetchError: String?,
    onFetchRepos: (String) -> Unit,
    isCreatingRepo: Boolean,
    repoCreationResult: String?,
    onCreateRepo: (String, String, Boolean, String) -> Unit,
    onClearRepoCreationResult: () -> Unit,
    onSave: (String, String, String, String, String, Boolean) -> Unit
) {
    var username by remember { mutableStateOf(config?.username ?: "") }
    var token by remember { mutableStateOf(config?.token ?: "") }
    var repoName by remember { mutableStateOf(config?.repositoryName ?: "") }
    var branchName by remember { mutableStateOf(config?.branchName ?: "main") }
    var syncMode by remember { mutableStateOf(config?.fileSyncMode ?: "ZIP") } // "ZIP" or "SOURCE_TREE"
    var isAutoSync by remember { mutableStateOf(config?.isAutoSyncEnabled ?: false) }

    var isTokenVisible by remember { mutableStateOf(false) }
    var showRepoListDialog by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        if (config != null) {
            username = config.username
            token = config.token
            repoName = config.repositoryName
            branchName = config.branchName
            syncMode = config.fileSyncMode
            isAutoSync = config.isAutoSyncEnabled
        }
    }

    if (showRepoListDialog) {
        GithubRepoSelectionDialog(
            repos = repos,
            isLoading = isFetchingRepos,
            error = repoFetchError,
            onDismiss = { showRepoListDialog = false },
            onSelectRepo = { fullName, owner ->
                repoName = fullName
                username = owner
                showRepoListDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "إعدادات الربط مستودع GitHub",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "أدخل بيانات المستودع والرمز المميز (PAT) للوصول إلى GitHub لإجراء المزامنة فوراً.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("اسم المستخدم في GitHub") },
                placeholder = { Text("e.g., github_username") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("رمز الوصول الشخصي (GitHub Access Token)") },
                visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                        Icon(
                            imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxxxx") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("اسم المستودع (Repository Slug)") },
                    placeholder = { Text("username/repository_name") },
                    modifier = Modifier.weight(1f)
                )

                if (token.isNotBlank()) {
                    Button(
                        onClick = {
                            onFetchRepos(token)
                            showRepoListDialog = true
                        },
                        modifier = Modifier
                            .height(56.dp)
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("جلب", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            var showCreateRepoDialog by remember { mutableStateOf(false) }

            if (showCreateRepoDialog) {
                GithubCreateRepoDialog(
                    token = token,
                    isCreating = isCreatingRepo,
                    result = repoCreationResult,
                    onDismiss = {
                        showCreateRepoDialog = false
                        onClearRepoCreationResult()
                    },
                    onCreate = { name, desc, isPriv ->
                        onCreateRepo(name, desc, isPriv, token)
                    }
                )
            }

            if (token.isNotBlank()) {
                Button(
                    onClick = { showCreateRepoDialog = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Icon(imageVector = Icons.Default.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("أو إنشاء مستودع جديد على GitHub بقرة زر", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = branchName,
                onValueChange = { branchName = it },
                label = { Text("Branch فرع العمل المفرع") },
                placeholder = { Text("main or master") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text("صيغة المزامنة المرفوعة (Sync Upload Format):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { syncMode = "ZIP" },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (syncMode == "ZIP") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (syncMode == "ZIP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = if (syncMode == "ZIP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "مذكرة ZIP مضغوطة",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (syncMode == "ZIP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "ملف واحد آمن ومسـتقر",
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { syncMode = "SOURCE_TREE" },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (syncMode == "SOURCE_TREE") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (syncMode == "SOURCE_TREE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = if (syncMode == "SOURCE_TREE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "مستودع كود كامل",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (syncMode == "SOURCE_TREE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "رفع كشجرة ملفات كود مهيأة",
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    onSave(username, token, repoName, branchName, syncMode, isAutoSync)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "حفظ إعدادات المستودع (Save)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 تلميحات مفيدة:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. لربط GitHub بنجاح، يُفضل توليد Token على موقع GitHub بصلاحية 'repo'.\n" +
                               "2. صيغة 'مستودع كود كامل' تقوم برفع وتحليل الملفات (مع معالجة مكرر الألوان والمانيفست) في المجلد مباشرة حتى يسهل قرائته ككود مفتوح المصدر!\n" +
                               "3. صيغة 'مذكرة ZIP' تنتج أرشيف مضغوط مستقر جداً ومناسب لإعادة الاستيراد المباشر في هواتف أخرى.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        item {
            val context = LocalContext.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            val pm = context.packageManager
                            val appInfo = pm.getApplicationInfo(context.packageName, 0)
                            val apkFile = File(appInfo.sourceDir)
                            val cacheApkFile = File(context.cacheDir, "SketchGit.apk")
                            apkFile.inputStream().use { input ->
                                cacheApkFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                cacheApkFile
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "شارك تطبيق SketchGit APK"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "فشل مشاركة التطبيق: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "مشاركة التطبيق APK",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "مشاركة تطبيق SketchGit (APK)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "استخرج وشارك ملف الـ APK الخاص بهذه النسخة الحالية من تطبيقك مباشرة وبكل سهولة.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "مهندس برمجيات عمار محمد فطيح",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Software Engineer Amar Mohamed Fteih © 2026",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ========================== LOGS TAB ==========================
@Composable
fun LogsTabContent(
    logs: List<SyncLog>,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "سجل المزامنة الفورية",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            IconButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "مسح كافة السجلات", tint = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AssignmentTurnedIn,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("السجل نظيف تماماً 😊", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("سوف تظهر تقارير المزامنة ونتائج الرفع هنا فور حدوثها.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (log.status == "SUCCESS") Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = log.projectName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (log.status == "SUCCESS") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = log.message,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========================== ADD PROJECT DIALOG ==========================
@Composable
fun AddProjectDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, pkg: String, ver: String, path: String, fixColors: Boolean, fixManifest: Boolean) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("com.my.project") }
    var ver by remember { mutableStateOf("1.0") }
    var path by remember { mutableStateOf("") }
    var fixColors by remember { mutableStateOf(true) }
    var fixManifest by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مسار للمزامنة يدوياً") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "مفيد جداً في حال تعذر فحص مجلد .sketchware التلقائي بسبب حماية Scoped Storage في إصدارات أندرويد الحديثة.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("رقم المشروع (ID) أو رمز تعريفي") },
                    placeholder = { Text("e.g. 601") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم التطبيق / المشروع") },
                    placeholder = { Text("e.g. My App") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("حزمة المشروع (Package Name)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("المسار الكامل للمجلد المحلي") },
                    placeholder = { Text("/sdcard/Download/MyProjSources") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { fixColors = !fixColors },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = fixColors, onCheckedChange = { fixColors = it })
                    Text("تصحيح مكرر الألوان", fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { fixManifest = !fixManifest },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = fixManifest, onCheckedChange = { fixManifest = it })
                    Text("تصحيح مكرر صلاحيات المانيفست", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (path.isNotBlank()) onAdd(id, name, pkg, ver, path, fixColors, fixManifest) },
                enabled = path.isNotBlank()
            ) {
                Text("إضافة المشروع")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ========================== GITHUB REPO SELECTION DIALOG ==========================
@Composable
fun GithubRepoSelectionDialog(
    repos: List<Map<String, String>>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelectRepo: (fullName: String, userName: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "اختر مستودعاً من حسابك (GitHub)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (error != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (repos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد مستودعات متاحة.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(repos) { repo ->
                            val fullName = repo["fullName"] ?: ""
                            val parts = fullName.split("/")
                            val userName = if (parts.size > 1) parts[0] else ""
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectRepo(fullName, userName) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isPrivate = repo["isPrivate"] == "true"
                                    Icon(
                                        imageVector = if (isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = repo["name"] ?: "",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = fullName,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val desc = repo["description"]
                                        if (!desc.isNullOrBlank()) {
                                            Text(
                                                text = desc,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ========================== GITHUB IMPORT PROJECTS DIALOG ==========================
@Composable
fun GithubImportProjectsDialog(
    token: String,
    configuredRepo: String,
    configuredBranch: String,
    repos: List<Map<String, String>>,
    isFetchingRepos: Boolean,
    backups: List<Map<String, String>>,
    isFetchingBackups: Boolean,
    backupFetchError: String?,
    onDismiss: () -> Unit,
    onFetchRepos: () -> Unit,
    onFetchBackups: (repo: String, branch: String) -> Unit,
    onImportBackup: (downloadUrl: String, fileName: String) -> Unit,
    onImportFullRepo: (repo: String, branch: String) -> Unit
) {
    var repositoryName by remember { mutableStateOf(configuredRepo) }
    var branchName by remember { mutableStateOf(configuredBranch.ifBlank { "main" }) }
    var importMode by remember { mutableStateOf("ZIPBALL") } // "ZIPBALL" or "BACKUPS"
    var showReposDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (repos.isEmpty() && token.isNotBlank()) {
            onFetchRepos()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "استيراد مشروع من GitHub",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "اختر المستودع والفرع ونوع الاستيراد لجلب الكود محلياً والتعديل عليه بسهولة كـ مشروع Sketchware نشط.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Repository Selection
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "المستودع المستهدف:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = repositoryName,
                            onValueChange = { repositoryName = it },
                            placeholder = { Text("owner/repo_name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = { showReposDropdown = !showReposDropdown }) {
                                    Icon(
                                        imageVector = if (showReposDropdown) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "اختيار مستودع"
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showReposDropdown,
                            onDismissRequest = { showReposDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 200.dp)
                        ) {
                            if (isFetchingRepos) {
                                DropdownMenuItem(
                                    text = { Text("جاري تحميل المستودعات...", fontSize = 12.sp) },
                                    onClick = {}
                                )
                            } else if (repos.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("لا توجد مستودعات ملوحة، انقر لتحديث", fontSize = 12.sp) },
                                    onClick = { onFetchRepos() }
                                )
                            } else {
                                repos.forEach { repo ->
                                    val name = repo["fullName"] ?: ""
                                    DropdownMenuItem(
                                        text = { Text(name, fontSize = 12.sp) },
                                        onClick = {
                                            repositoryName = name
                                            showReposDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Branch
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "الفرع (Branch):",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = branchName,
                        onValueChange = { branchName = it },
                        placeholder = { Text("main") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Modes tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { importMode = "ZIPBALL" },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (importMode == "ZIPBALL") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (importMode == "ZIPBALL") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = if (importMode == "ZIPBALL") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "مستودع كامل (Zipball)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { 
                                importMode = "BACKUPS"
                                if (repositoryName.isNotBlank()) {
                                    onFetchBackups(repositoryName, branchName)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (importMode == "BACKUPS") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (importMode == "BACKUPS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (importMode == "BACKUPS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "أرشيف Zip من backups/",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Detail Box depending on Mode
                if (importMode == "ZIPBALL") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "استيراد هيكل المشروع بالكامل",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "سيقوم التطبيق بتنزيل كامل محتويات المستودع كملف ZIP (Zipball) وفك ضغطه داخل مجلد المشاريع النشط فوراً ككود برمجية.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (repositoryName.isNotBlank()) {
                                        onImportFullRepo(repositoryName, branchName)
                                    }
                                },
                                enabled = repositoryName.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تنزيل واستيراد السورس كود الآن 🚀", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    // BACKUPS mode
                    if (isFetchingBackups) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (backupFetchError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = backupFetchError,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onFetchBackups(repositoryName, branchName) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("إعادة المحاولة", fontSize = 11.sp)
                            }
                        }
                    } else if (backups.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "لم يتم العثور على نسخ احتياطية (.zip) في مجلد backups/",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = { onFetchBackups(repositoryName, branchName) }) {
                                    Text("تحديث القائمة 🔄", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "النسخ الاحتياطية المتوفرة:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                TextButton(onClick = { onFetchBackups(repositoryName, branchName) }) {
                                    Text("تحديث 🔄", fontSize = 11.sp)
                                }
                            }

                            backups.forEach { backup ->
                                val name = backup["name"] ?: ""
                                val downloadUrl = backup["downloadUrl"] ?: ""
                                val sizeInBytes = backup["size"]?.toLongOrNull() ?: 0L
                                val sizeFormatted = if (sizeInBytes > 1024 * 1024) {
                                    String.format(Locale.US, "%.2f MB", sizeInBytes.toFloat() / (1024 * 1024))
                                } else {
                                    String.format(Locale.US, "%.2f KB", sizeInBytes.toFloat() / 1024)
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "الحجم: $sizeFormatted",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { onImportBackup(downloadUrl, name) },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(18.dp)
                                                )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "تحميل واستعادة",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun GithubCreateRepoDialog(
    token: String,
    isCreating: Boolean,
    result: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isPrivate: Boolean) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "إنشاء مستودع جديد على GitHub",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "سيقوم هذا بإنشاء مستودع جديد فارغ على حسابك مباشرة لتتمكن من رفع ومزامنة مشاريعك إليه.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("اسم المستودع") },
                    placeholder = { Text("مثال: MySuperProject") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("الوصف (اختياري)") },
                    placeholder = { Text("مثال: مستودع كود تطبيق سكيتشوير") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("مستودع خاص (Private Repository)", fontSize = 12.sp)
                }

                if (isCreating) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (result != null) {
                    val isSuccess = result == "SUCCESS"
                    Text(
                        text = if (isSuccess) "تم إنشاء المستودع بنجاح!" else result,
                        color = if (isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(repoName, description, isPrivate) },
                enabled = repoName.isNotBlank() && !isCreating
            ) {
                Text("إنشاء")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text("إلغاء")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditorLayout(
    project: SketchwareProject,
    workingRoot: File?,
    filesList: List<File>,
    selectedFile: File?,
    fileContent: String?,
    editorMessage: String?,
    onClose: () -> Unit,
    onSelectFile: (File) -> Unit,
    onSaveFile: (File, String) -> Unit,
    onDeleteFile: (File) -> Unit,
    onCreateFileOrFolder: (parentDir: File, name: String, isFolder: Boolean) -> Unit,
    onBuildProject: () -> Unit,
    onClearMessage: () -> Unit
) {
    val context = LocalContext.current
    var textContent by remember(fileContent) { mutableStateOf(fileContent ?: "") }
    var searchFilter by remember { mutableStateOf("") }

    // Dialog triggering states
    var showCreateDialog by remember { mutableStateOf(false) }
    var createInDir by remember { mutableStateOf<File?>(null) }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var newElementName by remember { mutableStateOf("") }

    val filteredFiles = remember(filesList, searchFilter, workingRoot) {
        if (workingRoot == null) emptyList() else {
            filesList.filter { file ->
                val relative = file.relativeTo(workingRoot).path
                searchFilter.isBlank() || relative.contains(searchFilter, ignoreCase = true)
            }
        }
    }

    // Display a Toast whenever there is an editor message
    LaunchedEffect(editorMessage) {
        if (editorMessage != null) {
            android.widget.Toast.makeText(context, editorMessage, android.widget.Toast.LENGTH_SHORT).show()
            onClearMessage()
        }
    }

    if (showCreateDialog && createInDir != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(if (isCreatingFolder) "إنشاء مجلد جديد" else "إنشاء ملف جديد") },
            text = {
                OutlinedTextField(
                    value = newElementName,
                    onValueChange = { newElementName = it },
                    label = { Text("الاسم") },
                    placeholder = { Text(if (isCreatingFolder) "مثال: src" else "مثال: Main.java") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newElementName.isNotBlank()) {
                            onCreateFileOrFolder(createInDir!!, newElementName, isCreatingFolder)
                            newElementName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("إنشاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "محرر: ${project.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "تعديل المجلدات والملفات المباشرة",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    if (selectedFile != null) {
                        IconButton(
                            onClick = { onSaveFile(selectedFile, textContent) },
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "حفظ الملف",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    IconButton(
                        onClick = onBuildProject,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "تجميع وحزم APK",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Left Half: File Explorer Sidebar
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(8.dp)
            ) {
                // Search & Filter
                OutlinedTextField(
                    value = searchFilter,
                    onValueChange = { searchFilter = it },
                    placeholder = { Text("بحث عن ملف...", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Root create action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("شجرة الملفات", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                if (workingRoot != null) {
                                    createInDir = workingRoot
                                    isCreatingFolder = false
                                    showCreateDialog = true
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NoteAdd,
                                contentDescription = "ملف جديد",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (workingRoot != null) {
                                    createInDir = workingRoot
                                    isCreatingFolder = true
                                    showCreateDialog = true
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "مجلد جديد",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

                // Files Tree Listing
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredFiles) { file ->
                        val depth = workingRoot?.let { getFileDepth(it, file) } ?: 0
                        val isDir = file.isDirectory
                        val isCurrentlySelected = selectedFile?.absolutePath == file.absolutePath

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isCurrentlySelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable {
                                    if (!isDir) {
                                        onSelectFile(file)
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .padding(start = (depth * 8).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = file.name,
                                fontSize = 11.sp,
                                fontWeight = if (isCurrentlySelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )

                            // Quick context action buttons (Add file in dir or Delete file/folder)
                            Row {
                                if (isDir) {
                                    IconButton(
                                        onClick = {
                                            createInDir = file
                                            isCreatingFolder = false
                                            showCreateDialog = true
                                        },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "إضافة في المجلد",
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onDeleteFile(file) },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "حذف",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            VerticalDivider()

            // Right Half: Active Code Editor Workspace
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .padding(8.dp)
            ) {
                if (selectedFile != null) {
                    val fileExtension = selectedFile.extension.lowercase(Locale.getDefault())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = selectedFile.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "الامتداد: $fileExtension | الحجم: ${selectedFile.length()} بايت",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { onSaveFile(selectedFile, textContent) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("حفظ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

                    TextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "اختر ملفاً من الشجرة للبدء في تعديله 📂",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getFileDepth(root: File, file: File): Int {
    var parent = file.parentFile
    var depth = 0
    while (parent != null && parent.absolutePath != root.absolutePath) {
        depth++
        parent = parent.parentFile
    }
    return depth
}

@Composable
fun BuildProgressDialog(
    buildState: com.example.ui.viewmodel.BuildStatusState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    if (buildState is com.example.ui.viewmodel.BuildStatusState.Idle) return

    val logsState = androidx.compose.foundation.lazy.rememberLazyListState()

    AlertDialog(
        onDismissRequest = {
            if (buildState !is com.example.ui.viewmodel.BuildStatusState.Building) {
                onDismiss()
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val icon = when (buildState) {
                    is com.example.ui.viewmodel.BuildStatusState.Building -> Icons.Default.Settings
                    is com.example.ui.viewmodel.BuildStatusState.Success -> Icons.Default.CheckCircle
                    is com.example.ui.viewmodel.BuildStatusState.Failure -> Icons.Default.Error
                    else -> Icons.Default.Settings
                }
                val tint = when (buildState) {
                    is com.example.ui.viewmodel.BuildStatusState.Building -> MaterialTheme.colorScheme.primary
                    is com.example.ui.viewmodel.BuildStatusState.Success -> Color(0xFF4CAF50)
                    is com.example.ui.viewmodel.BuildStatusState.Failure -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Icon(imageVector = icon, contentDescription = null, tint = tint)
                Text(
                    text = when (buildState) {
                        is com.example.ui.viewmodel.BuildStatusState.Building -> "جاري تجميع التطبيق (Build)..."
                        is com.example.ui.viewmodel.BuildStatusState.Success -> "تم التجميع بنجاح! 🎉"
                        is com.example.ui.viewmodel.BuildStatusState.Failure -> "فشل بناء التطبيق ❌"
                        else -> "تجميع التطبيق"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Progress Bar or Summary
                when (buildState) {
                    is com.example.ui.viewmodel.BuildStatusState.Building -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { buildState.progress },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = buildState.currentStep,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${(buildState.progress * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    is com.example.ui.viewmodel.BuildStatusState.Success -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("مسار ملف الـ APK:", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            Text(buildState.apkPath, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("الحجم النهائي للحزمة:", fontSize = 11.sp, color = Color.DarkGray)
                                Text(buildState.apkSize, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                            }
                        }
                    }
                    is com.example.ui.viewmodel.BuildStatusState.Failure -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text("وصف العطل:", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(buildState.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    else -> {}
                }

                // Terminal Shell Logs view
                val logsList = when (buildState) {
                    is com.example.ui.viewmodel.BuildStatusState.Building -> buildState.logs
                    is com.example.ui.viewmodel.BuildStatusState.Success -> buildState.logs
                    is com.example.ui.viewmodel.BuildStatusState.Failure -> buildState.logs
                    else -> emptyList()
                }

                Text("سجل التجميع البرمجي (Build Console Logs):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                
                LaunchedEffect(logsList.size) {
                    if (logsList.isNotEmpty()) {
                        logsState.animateScrollToItem(logsList.size - 1)
                    }
                }

                LazyColumn(
                    state = logsState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF151515), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logsList) { log ->
                        val color = if (log.contains("[ERROR]", ignoreCase = true) || log.contains("فشل", ignoreCase = true)) {
                            Color(0xFFE57373)
                        } else if (log.contains("[LINT WARNING]", ignoreCase = true)) {
                            Color(0xFFFFB74D)
                        } else if (log.contains("✓") || log.contains("نجاح", ignoreCase = true)) {
                            Color(0xFF81C784)
                        } else {
                            Color(0xFFE0E0E0)
                        }
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = color,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (buildState is com.example.ui.viewmodel.BuildStatusState.Success) {
                    Button(
                        modifier = Modifier.weight(1.2f),
                        onClick = {
                            val apkFile = File(buildState.apkPath)
                            if (apkFile.exists()) {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        apkFile
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/vnd.android.package-archive"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "شارك تطبيق APK المجمع"))
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "فشل المشاركة: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاركة وتنزيل", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.White
                        ),
                        onClick = {
                            val apkFile = File(buildState.apkPath)
                            if (apkFile.exists()) {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        apkFile
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "فشل التثبيت: ${e.localizedMessage}\nالرجاء تمكين مصادر غير معروفة.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تثبيت التطبيق", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    TextButton(onClick = onDismiss) {
                        Text("إغلاق")
                    }
                } else if (buildState is com.example.ui.viewmodel.BuildStatusState.Failure) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) {
                        Text("موافق")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Row {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("إلغاء التجميع")
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun GitHubBuildProgressDialog(
    buildState: com.example.ui.viewmodel.GitHubBuildState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    if (buildState is com.example.ui.viewmodel.GitHubBuildState.Idle) return

    AlertDialog(
        onDismissRequest = {
            if (buildState !is com.example.ui.viewmodel.GitHubBuildState.Triggering) {
                onDismiss()
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val icon = when (buildState) {
                    is com.example.ui.viewmodel.GitHubBuildState.Triggering -> Icons.Default.Settings
                    is com.example.ui.viewmodel.GitHubBuildState.Success -> Icons.Default.CheckCircle
                    is com.example.ui.viewmodel.GitHubBuildState.Failure -> Icons.Default.Error
                    else -> Icons.Default.Settings
                }
                val tint = when (buildState) {
                    is com.example.ui.viewmodel.GitHubBuildState.Triggering -> MaterialTheme.colorScheme.primary
                    is com.example.ui.viewmodel.GitHubBuildState.Success -> Color(0xFF4CAF50)
                    is com.example.ui.viewmodel.GitHubBuildState.Failure -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Icon(imageVector = icon, contentDescription = null, tint = tint)
                Text(
                    text = when (buildState) {
                        is com.example.ui.viewmodel.GitHubBuildState.Triggering -> "تفعيل بناء السحابة... ⚙️"
                        is com.example.ui.viewmodel.GitHubBuildState.Success -> "تم إطلاق البناء في GitHub! 🎉"
                        is com.example.ui.viewmodel.GitHubBuildState.Failure -> "خطأ تجميع GitHub ❌"
                        else -> "تجميع GitHub Actions"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (buildState) {
                    is com.example.ui.viewmodel.GitHubBuildState.Triggering -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = "جاري إنشاء ورفع ملف تكوين الأندرويد لـ GitHub Actions، ثم إرسال وتنشيط المحثّ التلقائي للبناء (GitHub Run Dispatch)... يرجى الانتظار.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    is com.example.ui.viewmodel.GitHubBuildState.Success -> {
                        Text(
                            text = "تم بنجاح ومثالية إطلاق خوادم البناء والتجميع في تطبيقك على GitHub.\n\nيمكنك الآن متابعة ومراقبة سير التجميع مباشرة وتحميل الحزمة APK النهائية فور انتهائها عبر الرابط أدناه.",
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = Color(0xFF2E7D32))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = buildState.runUrl,
                                color = Color(0xFF1B5E20),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is com.example.ui.viewmodel.GitHubBuildState.Failure -> {
                        Text(
                            text = "فشل في تجميع ومزامنة البناء السحابي عبر GitHub بسبب:\n\n${buildState.error}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (buildState is com.example.ui.viewmodel.GitHubBuildState.Success) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(buildState.runUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("فتح صفحة البناء", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                    Text("إغلاق")
                }
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun GithubProjectsTabContent(
    viewModel: SketchGitViewModel,
    config: GitHubConfig?,
    scanPath: String,
    onImportFullRepo: (token: String, repo: String, branch: String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("sketchware") }
    val searchResults by viewModel.githubSearchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearchingGithub.collectAsStateWithLifecycle()
    val searchError by viewModel.githubSearchError.collectAsStateWithLifecycle()

    // Auto-search on first load
    LaunchedEffect(Unit) {
        if (searchResults.isEmpty()) {
            viewModel.searchPublicGithub(config?.token, searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        
        // Search Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "استكشاف مشاريع وسورس كود GitHub 🔍",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "ابحث عن مشاريع سكيتشوير ومستودعات أندرويد الجاهزة وقم بتنزيلها كأكواد برمجية حية مباشرة لتعديلها وتجميعها وبنائها محلياً.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("بحث في مستودعات GitHub...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "مسح")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )
                    
                    Button(
                        onClick = { 
                            if (searchQuery.isNotBlank()) {
                                viewModel.searchPublicGithub(config?.token, searchQuery)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("بحث", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Search Results & States
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isSearching) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "جاري جلب المشاريع السحابية من GitHub...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (searchError != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "فشل في جلب المشاريع الملوحة من خوادم GitHub.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = searchError ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.searchPublicGithub(config?.token, searchQuery) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إعادة المحاولة")
                    }
                }
            } else if (searchResults.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "لم يتم العثور على أي نتائج بحث.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "جرب كلمات بحث أخرى (مثل: sketchware, android, java)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(searchResults) { repo ->
                        val fullName = repo["fullName"] ?: ""
                        val name = repo["name"] ?: ""
                        val description = repo["description"] ?: ""
                        val avatarUrl = repo["avatarUrl"] ?: ""
                        val stars = repo["stars"] ?: "0"
                        val forks = repo["forks"] ?: "0"
                        val language = repo["language"] ?: "Java"
                        val htmlUrl = repo["htmlUrl"] ?: ""
                        val defaultBranch = repo["defaultBranch"] ?: "main"

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (avatarUrl.isNotEmpty()) {
                                            AsyncImage(
                                                model = avatarUrl,
                                                contentDescription = "Avatar",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = fullName,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                if (description.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        lineHeight = 16.sp
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                // Stars, forks, language badges
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Stars
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Stars",
                                                tint = Color(0xFFFFB300),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = stars, fontSize = 10.sp)
                                        }
                                    }
                                    
                                    // Forks
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Forks",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = forks, fontSize = 10.sp)
                                        }
                                    }
                                    
                                    // Language Tag
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        ),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = language,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Import Full Project Button
                                    Button(
                                        onClick = {
                                            onImportFullRepo(config?.token ?: "", fullName, defaultBranch)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("تنزيل واستيراد المشروع", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    // Open in browser button
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
                                            context.startActivity(intent)
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = "عرض في المتصفح",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTabContent(
    projects: List<SketchwareProject>,
    config: GitHubConfig?,
    projectConflicts: Map<String, List<String>>,
    onTriggerRemoteBuild: (SketchwareProject) -> Unit,
    onResolveConflicts: (SketchwareProject, Boolean) -> Unit,
    onQuickSync: (String) -> Unit,
    onOpenEditor: (SketchwareProject) -> Unit,
    onBuildApk: (SketchwareProject) -> Unit,
    onScanClick: () -> Unit
) {
    val context = LocalContext.current
    val totalProjects = projects.size
    val totalConflicts = projectConflicts.values.count { it.isNotEmpty() }
    val successfullySyncedCount = projects.count { it.lastSyncStatus == "SUCCESS" }
    val failedSyncedCount = projects.count { it.lastSyncStatus == "FAILED" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Premium Software Developer Core Identity Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                        )
                    )
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.05f)
                                )
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Glowing architectural dynamic developer icon badge
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Engineering,
                                contentDescription = "المطور المعتمد عمار فطيح",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "المطور الرسمي الحصري",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 6.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "مهندس برمجيات عمار محمد فطيح",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.SansSerif
                            )
                            Text(
                                text = "هندسة النظم العبقرية والتطبيقات الذكية ذات البناء الآمن",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Stats Header Section
        item {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text(
                    text = "نظرة عامة وإحصاءات الأداء والربط 📊",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "لوحة تحكم حية لمتابعة المشاريع والتعارضات والمزامنة البرمجية",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Core Statistics Cards HUD
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Total Projects Info Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("المشاريع الكلية", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                text = totalProjects.toString(),
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Synced projects
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC8E6C9))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("المزامنة الناجحة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Text(
                                text = successfullySyncedCount.toString(),
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                color = Color(0xFF1B5E20)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Failed syncing status
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFCDD2))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("مشاكل المزامنة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                            }
                            Text(
                                text = failedSyncedCount.toString(),
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                color = Color(0xFFB71C1C)
                            )
                        }
                    }

                    // File Conflicts Card Alert Indicator
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (totalConflicts > 0) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (totalConflicts > 0) Color(0xFFFFE0B2) else Color.Transparent)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (totalConflicts > 0) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "تعارض الملفات",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (totalConflicts > 0) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = totalConflicts.toString(),
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                color = if (totalConflicts > 0) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Active File Conflicts Alert List Item
        if (totalConflicts > 0) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3E0), RoundedCornerShape(20.dp))
                        .border(1.5.dp, Color(0xFFEF6C00), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "تحذير أمني: تم كشف تعارض دمج دلالي فعال!",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = Color(0xFFE65100),
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "يوجد تعارض في سطور الأكواد البرمجية نتيجة للتعديلات المتزامنة. لحماية سلامة المشروع التجميعية وسورس كود التطبيق، يرجى تصفية التعارض فورا باختيار مسار الاستبقاء المفضل:",
                        fontSize = 11.sp,
                        color = Color(0xFF5D4037)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val conflictedProjects = projects.filter { projectConflicts[it.id]?.isNotEmpty() == true }
                    conflictedProjects.forEach { project ->
                        val files = projectConflicts[project.id] ?: emptyList()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFE0B2))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "📦 مشروع: " + project.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                files.forEach { relativePath ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = relativePath,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.DarkGray
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onResolveConflicts(project, true) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("الإبقاء على المحلي", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { onResolveConflicts(project, false) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF6C00)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("الإبقاء على السحابي", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Full Interactive Action Trigger Buttons for Project Hub
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "مستودع المزامنة السريع 🚀",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.SansSerif
                )

                Button(
                    onClick = onScanClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تدقيق وفحص سريع", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (projects.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("لا يوجد مشاريع مكتشفة حتى الآن.", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("انتقل لعلامة تبويب 'المشاريع' لتعديل مسار الفحص أو المزامنة والبدء البرمجي.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            items(projects) { project ->
                val filesConflicted = projectConflicts[project.id] ?: emptyList()
                val isConflicted = filesConflicted.isNotEmpty()

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isConflicted) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (isConflicted) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = project.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = if (isConflicted) Color(0xFFB71C1C) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "حزمة: ${project.packageName}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }

                            // Sync status chip visual indicator
                            val (chipColor, label, labelColor) = when {
                                isConflicted -> Triple(Color(0xFFFFCDD2), "هناك تعارض نشط! ⚠️", Color(0xFFC62828))
                                project.lastSyncStatus == "SUCCESS" -> Triple(Color(0xFFE8F5E9), "مزامنة آمنة ✓", Color(0xFF2E7D32))
                                project.lastSyncStatus == "FAILED" -> Triple(Color(0xFFFFCDD2), "فشلت المزامنة", Color(0xFFC62828))
                                else -> Triple(MaterialTheme.colorScheme.surfaceVariant, "غير متزامن", MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = chipColor),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = labelColor,
                                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Spacer(modifier = Modifier.height(12.dp))

                        // Trigger details & action hub
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val displayTime = if (project.lastSyncTimestamp > 0) {
                                val sdf = SimpleDateFormat("HH:mm - yyyy/MM/dd", Locale.getDefault())
                                "مزامنة: " + sdf.format(Date(project.lastSyncTimestamp))
                            } else {
                                "مزامنة: لم تتم بعد"
                            }
                            Text(
                                text = displayTime,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Open Editor Icon Button!
                                FilledIconButton(
                                    onClick = { onOpenEditor(project) },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "تعديل الملفات", modifier = Modifier.size(16.dp))
                                }

                                // ⚙️ Remote Cloud Builder on GitHub Actions!
                                val hasConfig = config != null && config.token.isNotBlank() && config.repositoryName.isNotBlank()
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("تجميع سحابي على GitHub Actions") } },
                                    state = rememberTooltipState()
                                ) {
                                    FilledIconButton(
                                        onClick = {
                                            if (hasConfig) onTriggerRemoteBuild(project)
                                            else {
                                                android.widget.Toast.makeText(context, "الرجاء ضبط إعدادات الـ GitHub أولاً!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1565C0)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "تجميع GitHub Actions", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }

                                // Quick Sync Button
                                FilledIconButton(
                                    onClick = { onQuickSync(project.id) },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Sync, contentDescription = "مزامنة سريعة بـ GitHub", tint = Color.White, modifier = Modifier.size(16.dp))
                                }

                                // Build local APK Button
                                FilledIconButton(
                                    onClick = { onBuildApk(project) },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Build, contentDescription = "تجميع APK محلي", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

