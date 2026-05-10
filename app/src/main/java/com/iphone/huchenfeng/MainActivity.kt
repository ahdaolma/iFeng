package com.iphone.huchenfeng

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.iphone.huchenfeng.ui.theme.LlamaChatTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

private const val DEFAULT_MODEL_PATH = "/sdcard/Download/hu_qwen25_Q4_K_M.gguf"
// ═══════════════════════════════════════════════════════════════════════════════
// 【重要】模型文件放置指南：
// 1. 将 GGUF 格式的模型文件放入手机存储的 "下载" 文件夹
//    路径示例：/storage/emulated/0/Download/model.gguf
//    或：/sdcard/Download/your_model.gguf
// 2. 模型文件命名建议：hu_qwen25_Q4_K_M.gguf
// 3. 文件大小要求：至少 10MB 以上
// 4. 支持格式：Q4_K_M, Q5_K_M, Q8_0 等 GGUF 量化格式
// ═══════════════════════════════════════════════════════════════════════════════

private val Context.dataStore by preferencesDataStore(name = "settings")
private val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")

class MainActivity : ComponentActivity() {

    companion object {
        var hasSplashBeenShown = false
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var vm: ChatViewModel

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            vm.handleFileSelection(it, this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        SettingsManager.init(applicationContext)
        prefs = getSharedPreferences("LlamaChatPrefs", Context.MODE_PRIVATE)

        val database = AppDatabase.getDatabase(applicationContext)
        val chatDao = database.chatDao()
        val factory = ChatViewModelFactory(chatDao)
        vm = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            var isShowSplash by remember { mutableStateOf(!hasSplashBeenShown) }

            val systemTheme = isSystemInDarkTheme()
            val isDarkModePersistedFlow = remember { context.dataStore.data.map { it[IS_DARK_MODE] } }
            val isDarkModePersisted by isDarkModePersistedFlow.collectAsState(initial = null)
            var isDarkMode by rememberSaveable { mutableStateOf(systemTheme) }
            var hasUserToggledTheme by remember { mutableStateOf(false) }

            LaunchedEffect(isDarkModePersisted) {
                isDarkModePersisted?.let { isDarkMode = it }
            }

            var themeRevealCenter by remember { mutableStateOf(Offset.Zero) }

            val currentBackgroundColor = if (isDarkMode) Color.Black else Color.White

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(currentBackgroundColor)
            ) {
                Crossfade(
                    targetState = isShowSplash,
                    animationSpec = tween(800),
                    label = "SplashTransition"
                ) { showSplash ->
                    if (showSplash) {
                        SplashScreen(onSplashFinished = {
                            isShowSplash = false
                            hasSplashBeenShown = true
                        })
                    } else {
                        AnimatedContent(
                            targetState = isDarkMode,
                            label = "ThemeCircularReveal",
                            transitionSpec = {
                                fadeIn(animationSpec = tween(600)) togetherWith fadeOut(animationSpec = tween(600)) using SizeTransform(clip = false)
                            },
                            contentAlignment = Alignment.Center
                        ) { targetTheme ->
                            val revealProgress = remember { androidx.compose.animation.core.Animatable(0f) }
                            LaunchedEffect(targetTheme) {
                                if (hasUserToggledTheme) {
                                    revealProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(600, easing = FastOutSlowInEasing)
                                    )
                                } else {
                                    revealProgress.snapTo(1f)
                                }
                            }

                            Box(
                                modifier = Modifier.clip(
                                    CircularRevealShape(revealProgress.value, themeRevealCenter)
                                )
                            ) {
                                LlamaChatTheme(darkTheme = targetTheme) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        MainScreen(
                                            factory = factory,
                                            isDarkMode = targetTheme,
                                            onThemeToggle = { clickOffset ->
                                                hasUserToggledTheme = true
                                                themeRevealCenter = clickOffset
                                                scope.launch {
                                                    context.dataStore.edit { prefs ->
                                                        prefs[IS_DARK_MODE] = !isDarkMode
                                                    }
                                                }
                                            }
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

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(factory: ChatViewModelFactory, isDarkMode: Boolean, onThemeToggle: (Offset) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activity = context as? MainActivity

    var hasPermission by remember { mutableStateOf(activity?.hasAllFilesAccess() ?: false) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = activity?.hasAllFilesAccess() ?: false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val vm: ChatViewModel = viewModel(factory = factory)
    val chat = vm.chatHistory
    val statusText by vm.statusText.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val sessions by vm.allSessions.collectAsState()
    val modelInfo by vm.modelInfo.collectAsState()
    val isThinking by vm.isThinking.collectAsState()
    val originalSourcePath by vm.originalSourcePath.collectAsState()
    val actualLoadingPath by vm.actualLoadingPath.collectAsState()

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var input by remember { mutableStateOf("") }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<Long?>(null) }
    var showRestartDialog by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSessionIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val isModelReady = modelStatus == ChatViewModel.ModelStatus.Ready

    LaunchedEffect(hasPermission, modelStatus) {
        val modelPath = SettingsManager.modelPath
        if (hasPermission && modelPath.isNotBlank() && modelStatus == ChatViewModel.ModelStatus.NotReady) {
            vm.loadModel(modelPath)
        }
    }

    LaunchedEffect(chat.size, isThinking) {
        val targetIndex = if (isThinking) {
            chat.size
        } else {
            (chat.size - 1).coerceAtLeast(0)
        }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(Unit) {
        listState.scrollToItem(chat.size.coerceAtLeast(0))
    }

    val isImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(isImeVisible) {
        if (isImeVisible && chat.isNotEmpty()) {
            val targetIndex = if (isThinking) {
                chat.size
            } else {
                (chat.size - 1).coerceAtLeast(0)
            }
            kotlinx.coroutines.delay(100)
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestartDialog = false
                SettingsManager.acknowledgeConfigChange()
            },
            icon = { Text("⚠️", style = MaterialTheme.typography.headlineMedium) },
            title = { Text("配置修改成功") },
            text = {
                Text("为了确保性能设置（如 GPU 加速和核心数）生效，请手动重启应用。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        SettingsManager.acknowledgeConfigChange()
                    }
                ) {
                    Text("我知道了")
                }
            }
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            onDismiss = {
                showSettingsSheet = false
                if (SettingsManager.hasConfigChanged() && modelStatus == ChatViewModel.ModelStatus.Ready) {
                    showRestartDialog = true
                }
            },
            onOpenFilePicker = { activity?.openFilePicker() },
            onLoadModel = {
                val modelPath = SettingsManager.modelPath
                if (modelPath.isNotBlank()) {
                    vm.loadModel(modelPath)
                }
                showSettingsSheet = false
            },
            onUnloadModel = { vm.unloadModel() },
            modelStatus = modelStatus,
            modelInfo = modelInfo,
            errorMessage = statusText,
            originalSourcePath = originalSourcePath,
            actualLoadingPath = actualLoadingPath
        )
    }

    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("删除对话？") },
            text = { Text("此操作将永久删除该对话及所有聊天记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = sessionToDelete
                        sessionToDelete = null
                        if (id != null) vm.deleteSession(id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除会话") },
            text = { Text("确认删除选中的 ${selectedSessionIds.size} 项对话？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedSessionIds.forEach { id ->
                            vm.deleteSession(id)
                        }
                        selectedSessionIds = emptySet()
                        isSelectionMode = false
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelectionMode) "已选择 ${selectedSessionIds.size} 项" else "对话列表",
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (isSelectionMode) {
                            Row {
                                TextButton(onClick = {
                                    isSelectionMode = false
                                    selectedSessionIds = emptySet()
                                }) {
                                    Text("取消")
                                }
                                if (selectedSessionIds.isNotEmpty()) {
                                    TextButton(onClick = { showBatchDeleteConfirm = true }) {
                                        Text("删除", color = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (!isSelectionMode) {
                        Button(
                            onClick = {
                                vm.createNewSession()
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                        ) {
                            Icon(Icons.Default.Add, null)
                            Text(" 新建对话")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    if (sessions.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无对话记录", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(items = sessions, key = { it.sessionId }) { session ->
                                SelectionSessionItem(
                                    session = session,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedSessionIds.contains(session.sessionId),
                                    onToggleSelection = {
                                        if (isSelectionMode) {
                                            selectedSessionIds = if (selectedSessionIds.contains(session.sessionId)) {
                                                selectedSessionIds - session.sessionId
                                            } else {
                                                selectedSessionIds + session.sessionId
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedSessionIds = setOf(session.sessionId)
                                        }
                                    },
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedSessionIds = if (selectedSessionIds.contains(session.sessionId)) {
                                                selectedSessionIds - session.sessionId
                                            } else {
                                                selectedSessionIds + session.sessionId
                                            }
                                        } else {
                                            vm.loadSession(session.sessionId)
                                            scope.launch { drawerState.close() }
                                        }
                                    },
                                    onDelete = {
                                        if (!isSelectionMode) {
                                            sessionToDelete = session.sessionId
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentWindowInsets = WindowInsets(0.dp),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    isReady = isModelReady,
                    isLoading = modelStatus == ChatViewModel.ModelStatus.Loading,
                    modelStatus = modelStatus,
                    onSet = { showSettingsSheet = true },
                    onMenu = { scope.launch { drawerState.open() } },
                    isDarkMode = isDarkMode,
                    onThemeToggle = onThemeToggle
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (!hasPermission) {
                        PermissionGuide(
                            onRequestPermission = { activity?.requestStoragePermission() }
                        )
                    } else {
                        ChatList(
                            msgs = chat,
                            state = listState,
                            isThinking = isThinking,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                BottomInputBar(
                    text = input,
                    onValueChange = { input = it },
                    onSend = {
                        val t = input
                        input = ""
                        vm.send(t)
                    },
                    isModelReady = isModelReady && hasPermission,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    onDismiss: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    modelStatus: ChatViewModel.ModelStatus,
    modelInfo: String?,
    errorMessage: String?,
    originalSourcePath: String,
    actualLoadingPath: String
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var threads by remember { mutableStateOf(SettingsManager.threads.toFloat()) }
    var contextSize by remember { mutableStateOf(SettingsManager.contextSize) }
    var useGpu by remember { mutableStateOf(SettingsManager.useGpu) }

    val contextSizeOptions = listOf(1024, 2048, 4096)
    var contextSizeExpanded by remember { mutableStateOf(false) }

    val modelPath = originalSourcePath
    val modelFileName = if (modelPath.isNotBlank()) modelPath.substringAfterLast("/") else ""
    var showPathDialog by remember { mutableStateOf(false) }

    val downloadLink = "https://bestfile.io/en/Z93TXZf9W478Zv7/file"

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("模型位置") },
            text = {
                Column {
                    Text(
                        text = if (modelStatus == ChatViewModel.ModelStatus.Ready && actualLoadingPath.isNotEmpty()) {
                            actualLoadingPath
                        } else {
                            "未加载模型"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(actualLoadingPath))
                    showPathDialog = false
                }) {
                    Text("复制路径")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPathDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text(
                "高级设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            Text("模型管理", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFilePicker() }
                        .padding(12.dp)
                ) {
                    Text(
                        text = "当前模型",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (modelPath.isNotBlank()) {
                        Text(
                            text = modelFileName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            IconButton(
                                onClick = { showPathDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = "路径详情",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = modelPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            text = "点击选择模型文件",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFFF4D4F)
                        )
                    }
                }
            }

        // --- 1. 下载链接模块 ---
        Text(
            text = "下载链接",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Link, 
                    contentDescription = "链接",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = downloadLink,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(downloadLink))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制链接", tint = Color.Gray)
                }
            }
        }

        // --- CPU 线程设置 ---
        Text(
            text = "CPU 线程",
            style = MaterialTheme.typography.titleSmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = threads,
                onValueChange = {
                    threads = it
                    SettingsManager.threads = it.toInt()
                },
                valueRange = 1f..8f,
                steps = 6,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${threads.toInt()}",
                modifier = Modifier.width(32.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // --- 上下文大小设置 ---
        Text(
            text = "上下文大小",
            style = MaterialTheme.typography.titleSmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        ExposedDropdownMenuBox(
            expanded = contextSizeExpanded,
            onExpandedChange = { contextSizeExpanded = !contextSizeExpanded }
        ) {
            OutlinedTextField(
                value = contextSize.toString(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = contextSizeExpanded) }
            )
            ExposedDropdownMenu(
                expanded = contextSizeExpanded,
                onDismissRequest = { contextSizeExpanded = false }
            ) {
                contextSizeOptions.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.toString()) },
                        onClick = {
                            contextSize = size
                            SettingsManager.contextSize = size
                            contextSizeExpanded = false
                        }
                    )
                }
            }
        }

        // --- GPU 加速设置 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GPU 加速", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                Text(
                    "Adreno GPU 加速 (实验性)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Switch(
                checked = useGpu,
                onCheckedChange = {
                    useGpu = it
                    SettingsManager.useGpu = it
                }
            )
        }

        // --- 2. 错误信息显示 ---
        if (modelStatus == ChatViewModel.ModelStatus.Error && !errorMessage.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEB)),
                border = BorderStroke(1.dp, Color.Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🔴 引擎故障日志",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color.Red,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // --- 2. 模型运行参数模块 ---
        Text(
            text = "模型运行参数",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = modelInfo ?: "",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                ),
                color = Color.DarkGray,
                modifier = Modifier.padding(16.dp)
            )
        }

        // --- 3. 底部版本号落款 ---
        Spacer(modifier = Modifier.height(24.dp))

        val uriHandler = LocalUriHandler.current
        Text(
            text = "开源地址: https://github.com/ahdaolma/iFeng",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/ahdaolma/iFeng")
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "i风 v1.0.0",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = TextAlign.Center,
            color = Color.Gray,
            style = MaterialTheme.typography.labelMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (modelStatus == ChatViewModel.ModelStatus.Ready) {
                OutlinedButton(
                    onClick = onUnloadModel,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text("卸载模型", color = Color.Red)
                }
            }

            Button(
                onClick = onLoadModel,
                modifier = Modifier.weight(1f),
                enabled = modelPath.isNotBlank() && 
                          modelStatus != ChatViewModel.ModelStatus.Loading &&
                          modelStatus != ChatViewModel.ModelStatus.Copying
            ) {
                when (modelStatus) {
                    ChatViewModel.ModelStatus.Copying, ChatViewModel.ModelStatus.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (modelStatus == ChatViewModel.ModelStatus.Copying) "导入中..." else "加载中...")
                    }
                    else -> {
                        Text(if (modelStatus == ChatViewModel.ModelStatus.Ready) "重新加载" else "加载模型")
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun StatusBar(
    text: String,
    isError: Boolean,
    isLoading: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isError -> Color.Red
                    !hasPermission -> Color(0xFFFF9800)
                    else -> Color.Gray
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        val lastUsedText = "上次连麦时间：${SettingsManager.getFormattedLastUsedTime()}"
        Text(
            text = lastUsedText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PermissionGuide(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF6366F1)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "需要存储权限",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "为了读取 GGUF 模型文件，请授予存储权限",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("授予权限")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectionSessionItem(
    session: ChatSession,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val dateString = remember(session.timestamp) { dateFormat.format(Date(session.timestamp)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) {
                        onLongPress()
                    }
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            if (!isSelectionMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "删除",
                        tint = Color(0xFFFF4D4F)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val dateString = remember(session.timestamp) { dateFormat.format(Date(session.timestamp)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "删除",
                    tint = Color(0xFFFF4D4F)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    isReady: Boolean,
    isLoading: Boolean,
    modelStatus: ChatViewModel.ModelStatus,
    onSet: () -> Unit,
    onMenu: () -> Unit,
    isDarkMode: Boolean,
    onThemeToggle: (Offset) -> Unit
) {
    var iconCenter by remember { mutableStateOf(Offset.Zero) }

    val statusColor = when {
        modelStatus == ChatViewModel.ModelStatus.Copying -> Color.Blue
        modelStatus == ChatViewModel.ModelStatus.Loading -> Color(0xFFFF9800)
        modelStatus == ChatViewModel.ModelStatus.Ready -> Color(0xFF10B981)
        modelStatus == ChatViewModel.ModelStatus.Error -> Color.Red
        else -> Color.Gray
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("i风", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, null)
                }
            },
            actions = {
                IconButton(
                    onClick = { onThemeToggle(iconCenter) },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        iconCenter = coordinates.boundsInWindow().center
                    }
                ) {
                    AnimatedContent(
                        targetState = isDarkMode,
                        transitionSpec = {
                            if (targetState) {
                                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                    slideOutVertically { height -> -height } + fadeOut())
                            } else {
                                (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                    slideOutVertically { height -> height } + fadeOut())
                            }
                        },
                        label = "IconAnimation"
                    ) { dark ->
                        Icon(
                            imageVector = if (dark) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = if (dark) "切换到日间" else "切换到夜间",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onSet) {
                    Icon(Icons.Default.Settings, null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding()
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ChatList(
    msgs: List<ChatViewModel.UIChatMessage>,
    state: LazyListState,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = state,
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items = msgs, key = { it.id }) { msg ->
            when (msg.role) {
                ChatViewModel.Role.Ai -> AiMessageItem(msg)
                ChatViewModel.Role.User -> UserMessageItem(msg)
            }
        }

        if (isThinking) {
            item(key = "thinking_animation") {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                        .size(width = 120.dp, height = 40.dp)
                ) {
                    GeminiStyleThinkingAnimation(
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiStyleThinkingAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")

    val colors = listOf(
        Color(0xFF4285F4),
        Color(0xFFEA4335),
        Color(0xFFFBBC05),
        Color(0xFF34A853)
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0..3) {
            val delay = i * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale_$i"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha_$i"
            )

            Box(
                modifier = Modifier
                    .size((8 * scale).dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(colors[i])
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val dots = listOf(
        remember { androidx.compose.animation.core.Animatable(0f) },
        remember { androidx.compose.animation.core.Animatable(0f) },
        remember { androidx.compose.animation.core.Animatable(0f) }
    )

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            kotlinx.coroutines.delay(index * 150L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 300, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { animatable ->
            val offset = (animatable.value * -5f).dp
            Box(
                modifier = Modifier
                    .offset(y = offset)
                    .size(5.dp)
                    .background(Color.Gray, CircleShape)
            )
        }
    }
}

@Composable
private fun AiMessageItem(msg: ChatViewModel.UIChatMessage) {
    val isNewMessage = remember(msg.id) {
        System.currentTimeMillis() - msg.timestamp < 1000
    }

    if (isNewMessage) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(msg.id) { visible = true }

        val offsetX by animateDpAsState(
            targetValue = if (visible) 0.dp else (-50).dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "ai_offset_x"
        )
        val offsetY by animateDpAsState(
            targetValue = if (visible) 0.dp else 20.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "ai_offset_y"
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "ai_alpha"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.toPx()
                    translationY = offsetY.toPx()
                    this.alpha = alpha
                },
            horizontalAlignment = Alignment.Start
        ) {
            AiMessageContent(msg)
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            AiMessageContent(msg)
        }
    }
}

@Composable
private fun AiMessageContent(msg: ChatViewModel.UIChatMessage) {
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        shadowElevation = 1.dp
    ) {
        if (msg.isStreaming) {
            val textString = buildAnnotatedString {
                append(msg.text)
                if (msg.text.isNotEmpty()) {
                    appendInlineContent("typing_indicator", " ")
                }
            }

            val inlineContent = mapOf(
                "typing_indicator" to InlineTextContent(
                    Placeholder(
                        width = 30.sp,
                        height = 16.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        TypingIndicator()
                    }
                }
            )

            Text(
                text = textString,
                inlineContent = inlineContent,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                modifier = Modifier.padding(12.dp)
            )
        } else {
            MarkdownText(
                markdown = msg.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                modifier = Modifier
                    .padding(12.dp)
                    .graphicsLayer { clip = true }
            )
        }
    }
    Spacer(modifier = Modifier.height(3.dp))
    val timeText = rememberTimestamp(msg.id, msg.timestamp)
    Text(
        text = timeText,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    )
}

@Composable
private fun UserMessageItem(msg: ChatViewModel.UIChatMessage) {
    val isNewMessage = remember(msg.id) {
        System.currentTimeMillis() - msg.timestamp < 1000
    }

    if (isNewMessage) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(msg.id) { visible = true }

        val offsetX by animateDpAsState(
            targetValue = if (visible) 0.dp else 50.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "user_offset_x"
        )
        val offsetY by animateDpAsState(
            targetValue = if (visible) 0.dp else 20.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "user_offset_y"
        )
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "user_alpha"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.toPx()
                    translationY = offsetY.toPx()
                    this.alpha = alpha
                },
            horizontalAlignment = Alignment.End
        ) {
            UserMessageContent(msg)
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            UserMessageContent(msg)
        }
    }
}

@Composable
private fun UserMessageContent(msg: ChatViewModel.UIChatMessage) {
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        color = Color(0xFF6366F1),
        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    ) {
        Text(
            text = msg.text,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
        )
    }
    Spacer(modifier = Modifier.height(3.dp))
    val timeText = rememberTimestamp(msg.id, msg.timestamp)
    Text(
        text = timeText,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun rememberTimestamp(msgId: String, timestamp: Long): String {
    var timeText by remember(msgId) { mutableStateOf(formatTimestamp(timestamp)) }
    var refreshKey by remember(msgId) { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            kotlinx.coroutines.delay(60_000)
            timeText = formatTimestamp(timestamp)
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60_000)
        refreshKey++
    }

    return timeText
}

@Composable
private fun BottomInputBar(
    text: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isModelReady: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape),
                enabled = isModelReady,
                maxLines = 4,
                placeholder = {
                    Text(
                        if (isModelReady) "输入消息..." else "请先加载模型",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = isModelReady && text.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isModelReady && text.isNotBlank())
                            Color(0xFF6366F1)
                        else
                            Color.Gray
                    )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

class CircularRevealShape(private val progress: Float, private val centerOffset: Offset) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline {
        val center = if (centerOffset == Offset.Zero) Offset(size.width, 0f) else centerOffset
        val maxRadius = hypot(
            hypot(center.x.toDouble(), center.y.toDouble()).toFloat(),
            hypot((size.width - center.x).toDouble(), (size.height - center.y).toDouble()).toFloat()
        )
        val currentRadius = maxRadius * progress
        return Outline.Generic(Path().apply {
            addOval(Rect(center = center, radius = currentRadius))
        })
    }
}

@Composable
private fun SplashScreen(onSplashFinished: () -> Unit) {
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        rotation.animateTo(
            targetValue = 360f,
            animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
        )
        delay(500)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 12f * density
            },
            contentAlignment = Alignment.Center
        ) {
            when {
                rotation.value <= 90f -> {
                    Icon(
                        imageVector = Icons.Rounded.Android,
                        contentDescription = "Android",
                        modifier = Modifier.size(120.dp),
                        tint = Color(0xFF3DDC84)
                    )
                }
                rotation.value <= 270f -> {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_apple_logo),
                        contentDescription = "Apple Logo",
                        modifier = Modifier
                            .size(120.dp)
                            .graphicsLayer { rotationY = 180f },
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                else -> {
                    val alphaFade = (rotation.value - 270f) / 90f
                    Image(
                        painter = painterResource(id = R.drawable.ic_huchenfeng),
                        contentDescription = "Hu Chenfeng Logo",
                        modifier = Modifier
                            .size(120.dp)
                            .alpha(alphaFade.coerceIn(0f, 1f))
                    )
                }
            }
        }
    }
}