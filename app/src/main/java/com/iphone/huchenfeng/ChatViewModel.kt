package com.iphone.huchenfeng

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ChatViewModel(private val chatDao: ChatDao) : ViewModel() {

    private val engine = LlamaEngine.getInstance()

    private val _allSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val allSessions: StateFlow<List<ChatSession>> = _allSessions.asStateFlow()

    val chatHistory = mutableStateListOf<UIChatMessage>()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.NotReady)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _modelInfo = MutableStateFlow<String?>(null)
    val modelInfo: StateFlow<String?> = _modelInfo.asStateFlow()

    private val _originalSourcePath = MutableStateFlow("")
    val originalSourcePath: StateFlow<String> = _originalSourcePath.asStateFlow()

    private val _actualLoadingPath = MutableStateFlow("")
    val actualLoadingPath: StateFlow<String> = _actualLoadingPath.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private var currentSessionId by mutableStateOf(-1L)
    private var generationJob: Job? = null

    enum class ModelStatus { NotReady, Copying, Loading, Ready, Error }
    enum class Role { User, Ai }
    data class UIChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val role: Role,
        val text: String,
        val isStreaming: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    init {
        checkModelStatus()
        observeSessions()
        initSavedPath()
    }

    private fun initSavedPath() {
        viewModelScope.launch {
            val savedPath = SettingsManager.modelPath
            if (savedPath.isNotEmpty() && File(savedPath).exists()) {
                _originalSourcePath.value = savedPath
                _actualLoadingPath.value = savedPath
                loadModel(savedPath)
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            chatDao.getAllSessions().collectLatest { sessions ->
                _allSessions.value = sessions
            }
        }
    }

    private fun checkModelStatus() {
        if (engine.isLoaded) {
            _modelStatus.value = ModelStatus.Ready
            try {
                _modelInfo.value = engine.getModelInfo()
            } catch (e: Exception) {
                _modelInfo.value = null
            }
            val savedPath = SettingsManager.modelPath
            if (savedPath.isNotEmpty()) {
                _originalSourcePath.value = savedPath
                _actualLoadingPath.value = savedPath
            }
            _statusText.value = ""
        } else {
            _modelStatus.value = ModelStatus.NotReady
            _statusText.value = ""
        }
    }

    fun loadModel(path: String) {
        // 【防呆护盾 1】：如果当前正在加载中，坚决拦截用户的疯狂连点！
        if (_modelStatus.value == ModelStatus.Loading) {
            return
        }

        // 【防呆护盾 2】：如果选的路径和当前正在运行的路径完全一样，且已经处于 Ready 状态
        // 直接拦截，不仅不崩溃，还给用户一个友好的提示！
        if (engine.isLoaded && engine.modelPath == path && _modelStatus.value == ModelStatus.Ready) {
            _statusText.value = "该模型已在完美运行中，无需重复加载！"
            return
        }

        // 状态准备更新为 Loading...
        _modelStatus.value = ModelStatus.Loading
        _statusText.value = "准备装载神经引擎..."

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 【核心内存修复】：在装载新模型之前，必须释放内存中的旧模型！
                    try {
                        engine.unloadModel()
                    } catch (e: Exception) {
                        // 忽略卸载旧模型时的报错，可能原本就没加载
                    }

                    val file = File(path)

                    if (!file.exists()) {
                        throw IllegalStateException("模型文件不存在: $path")
                    }

                    if (!file.isFile) {
                        throw IllegalStateException("路径不是文件: $path")
                    }

                    val fileSize = file.length()
                    if (fileSize < 10 * 1024 * 1024) {
                        throw IllegalStateException("模型文件过小 (${fileSize / (1024 * 1024)}MB)")
                    }

                    engine.loadModel(path, SettingsManager.threads, SettingsManager.contextSize, SettingsManager.useGpu)
                }

                _modelStatus.value = ModelStatus.Ready
                _modelInfo.value = engine.getModelInfo()
                _originalSourcePath.value = path
                _actualLoadingPath.value = path
                SettingsManager.modelPath = path
                _statusText.value = ""
            } catch (e: Exception) {
                _modelStatus.value = ModelStatus.Error
                _statusText.value = "加载失败: ${e.message}"
            }
        }
    }

    fun handleFileSelection(uri: Uri, context: Context) {
        _modelStatus.value = ModelStatus.Copying
        _statusText.value = "正在解析文件路径..."

        viewModelScope.launch {
            try {
                val realPath = withContext(Dispatchers.IO) {
                    UriUtils.getRealPathFromURI(context, uri)
                }

                if (realPath != null && File(realPath).exists()) {
                    _originalSourcePath.value = realPath
                    _actualLoadingPath.value = realPath
                    _statusText.value = "✓ 直接加载原文件，0字节复制！"
                    loadModel(realPath)
                } else {
                    _modelStatus.value = ModelStatus.Error
                    _statusText.value = "无法将该文件解析为物理路径，请尝试将模型移动到 Download 根目录后再选。"
                }
            } catch (e: Exception) {
                _modelStatus.value = ModelStatus.Error
                _statusText.value = "路径解析致命错误: ${e.message}"
            }
        }
    }



    fun unloadModel() {
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
        }

        engine.unloadModel()
        _modelStatus.value = ModelStatus.NotReady
        _modelInfo.value = null
        _statusText.value = ""
    }

    fun send(userInput: String) {
        if (userInput.isBlank()) return

        if (_modelStatus.value != ModelStatus.Ready) {
            _statusText.value = "请先加载模型"
            return
        }

        if (generationJob?.isActive == true) {
            _statusText.value = "正在生成中，请稍候"
            return
        }

        generationJob = viewModelScope.launch(Dispatchers.Main) {
            val sessionId = if (currentSessionId == -1L) {
                val newSession = ChatSession(
                    title = userInput.take(20).replace("\n", " ") + if (userInput.length > 20) "..." else "",
                    timestamp = System.currentTimeMillis()
                )
                val id = withContext(Dispatchers.IO) {
                    chatDao.insertSession(newSession)
                }
                currentSessionId = id
                id
            } else {
                currentSessionId
            }

            chatHistory.add(UIChatMessage(role = Role.User, text = userInput))

            SettingsManager.lastUsedTime = System.currentTimeMillis()

            viewModelScope.launch(Dispatchers.IO) {
                chatDao.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        isUser = true,
                        content = userInput,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            _isThinking.value = true
            _statusText.value = ""

            val systemMsg = """
你是户晨风。你正在直播连麦。
核心思想：人分“苹果人”和“安卓人”。你鄙视“能用就行、凑合”的“安卓逻辑”。
你的标准：手机就是苹果，电车就是特斯拉，超市就是山姆。你看不起国产平替，称国产品牌华为为“菊花”、称比亚迪为“碧养地”。
你的态度：狂妄傲慢，面对穷人抬杠直接说“月入5000不配和我对话”、“翻翻你口袋是不是比脸干净”。绝对不承认自己是AI。
互动规则：对待连麦的“安卓人”极度刻薄，但只要听到刷礼物、送火箭，立刻变脸大喊“感谢优秀的某某总破费了”。遇到宏大叙事直接打断。
""".trimIndent()

            val formattedPrompt = buildPrompt(userInput, systemMsg)

            var aiMsgId: String? = null
            val textBuffer = StringBuilder()

            _isThinking.value = true
            _statusText.value = ""

            generationJob = viewModelScope.launch {
                try {
                    engine.generateAsFlow(formattedPrompt)
                        .flowOn(Dispatchers.IO)
                        .buffer(64)
                        .collect { piece ->
                            withContext(Dispatchers.Main) {
                                if (aiMsgId == null) {
                                    val newMsg = UIChatMessage(role = Role.Ai, text = piece, isStreaming = true)
                                    aiMsgId = newMsg.id
                                    textBuffer.clear()
                                    textBuffer.append(piece)
                                    chatHistory.add(newMsg)
                                    _isThinking.value = false
                                } else {
                                    textBuffer.append(piece)
                                    val currentIndex = chatHistory.indexOfFirst { it.id == aiMsgId }
                                    if (currentIndex != -1) {
                                        chatHistory[currentIndex] = chatHistory[currentIndex].copy(
                                            text = textBuffer.toString(),
                                            isStreaming = true
                                        )
                                    }
                                }
                            }
                        }

                    withContext(Dispatchers.Main) {
                        _isThinking.value = false
                        if (aiMsgId != null) {
                            val finalIndex = chatHistory.indexOfFirst { it.id == aiMsgId }
                            if (finalIndex != -1) {
                                val finalMsg = chatHistory[finalIndex]
                                chatHistory[finalIndex] = finalMsg.copy(
                                    isStreaming = false,
                                    text = finalMsg.text
                                )
                            }
                        }
                    }

                    withContext(Dispatchers.IO) {
                        if (aiMsgId != null) {
                            val finalIndex = chatHistory.indexOfFirst { it.id == aiMsgId }
                            if (finalIndex != -1) {
                                val finalMsg = chatHistory[finalIndex]
                                chatDao.insertMessage(
                                    ChatMessage(
                                        sessionId = sessionId,
                                        isUser = false,
                                        content = finalMsg.text,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )

                                if (userInput.isNotEmpty()) {
                                    val preview = userInput.take(15)
                                    chatDao.updateSessionTitle(
                                        sessionId,
                                        "$preview... (${finalMsg.text.take(15)})"
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _isThinking.value = false
                        if (aiMsgId != null) {
                            val errorIndex = chatHistory.indexOfFirst { it.id == aiMsgId }
                            if (errorIndex != -1) {
                                chatHistory[errorIndex] = chatHistory[errorIndex].copy(
                                    text = "Generation error: ${e.message}",
                                    isStreaming = false
                                )
                            }
                        } else {
                            chatHistory.add(UIChatMessage(
                                role = Role.Ai,
                                text = "Generation error: ${e.message}",
                                isStreaming = false
                            ))
                        }
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null

        val streamingIndex = chatHistory.indexOfFirst { it.isStreaming }
        if (streamingIndex != -1) {
            chatHistory[streamingIndex] = chatHistory[streamingIndex].copy(isStreaming = false)
        }

        _statusText.value = ""
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.deleteMessagesBySession(sessionId)
            chatDao.deleteSession(sessionId)

            viewModelScope.launch(Dispatchers.Main) {
                if (currentSessionId == sessionId) {
                    chatHistory.clear()
                    currentSessionId = -1L
                    if (engine.isLoaded) {
                        engine.clearCache()
                    }
                    _statusText.value = ""
                }
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.deleteMessagesBySession(currentSessionId)
        }

        chatHistory.clear()
        currentSessionId = -1L

        if (engine.isLoaded) {
            engine.clearCache()
        }
        _statusText.value = ""
    }

    fun loadSession(sessionId: Long) {
        chatHistory.clear()

        viewModelScope.launch(Dispatchers.IO) {
            val messages = chatDao.getMessagesForSession(sessionId)

            viewModelScope.launch(Dispatchers.Main) {
                messages.forEach { msg ->
                    val role = if (msg.isUser) Role.User else Role.Ai
                    chatHistory.add(UIChatMessage(role = role, text = msg.content))
                }
                currentSessionId = sessionId

                if (engine.isLoaded) {
                    engine.clearCache()
                }
                _statusText.value = ""
            }
        }
    }

    private fun buildPrompt(userInput: String, systemMsg: String): String {
        val sb = StringBuilder()

        sb.append("<|im_start|>system\n${systemMsg}<|im_end|>\n")
        sb.append("<|im_start|>user\n${userInput}<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }
}