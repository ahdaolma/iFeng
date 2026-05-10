package com.iphone.huchenfeng

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class LlamaEngine private constructor() {

    interface TokenCallback {
        fun onToken(tokenPiece: String)
        fun onComplete()
        fun onError(message: String)
    }

    @Volatile
    private var _isLoaded = false
    val isLoaded: Boolean get() = _isLoaded

    @Volatile
    private var _modelPath: String? = null
    val modelPath: String? get() = _modelPath

    external fun initModel(modelPath: String, threads: Int, ctxSize: Int, useGpu: Boolean): Boolean
    external fun isModelLoaded(): Boolean
    external fun freeModel()
    external fun clearCache()
    external fun getModelInfo(): String
    external fun generateResponse(prompt: String, callback: TokenCallback)
    external fun generateResponseSync(prompt: String): String

    suspend fun loadModel(
        path: String,
        threads: Int = SettingsManager.threads,
        ctxSize: Int = SettingsManager.contextSize,
        useGpu: Boolean = SettingsManager.useGpu
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)

            if (!file.exists()) {
                throw IllegalStateException("模型文件不存在: $path")
            }

            if (!file.isFile) {
                throw IllegalStateException("路径不是文件: $path")
            }

            if (file.length() < 1024 * 1024) {
                throw IllegalStateException("模型文件过小，可能是损坏: ${file.length()} bytes")
            }

            val success = initModel(path, threads, ctxSize, useGpu)
            if (!success) {
                throw IllegalStateException("模型初始化失败，请检查文件完整性")
            }

            _isLoaded = true
            _modelPath = path
        }
    }

    fun unloadModel() {
        if (_isLoaded) {
            freeModel()
            _isLoaded = false
            _modelPath = null
        }
    }

    fun generateAsFlow(prompt: String): Flow<String> = callbackFlow {
        if (!_isLoaded) {
            close(IllegalStateException("模型未加载"))
            return@callbackFlow
        }

        val callback = object : TokenCallback {
            override fun onToken(tokenPiece: String) {
                trySend(tokenPiece)
            }

            override fun onComplete() {
                close()
            }

            override fun onError(message: String) {
                close(IllegalStateException(message))
            }
        }

        try {
            generateResponse(prompt, callback)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { }
    }

    suspend fun generateWithResult(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!_isLoaded) {
                throw IllegalStateException("模型未加载")
            }
            generateResponseSync(prompt)
        }
    }

    fun generateBlocking(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!_isLoaded) {
            onError("模型未加载")
            return
        }

        val callback = object : TokenCallback {
            override fun onToken(tokenPiece: String) {
                onToken(tokenPiece)
            }

            override fun onComplete() {
                onComplete()
            }

            override fun onError(message: String) {
                onError(message)
            }
        }

        try {
            generateResponse(prompt, callback)
        } catch (e: Exception) {
            onError(e.message ?: "未知错误")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: LlamaEngine? = null

        init {
            System.loadLibrary("native-lib")
        }

        fun getInstance(): LlamaEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlamaEngine().also { INSTANCE = it }
            }
        }
    }
}
