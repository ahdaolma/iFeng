package com.iphone.huchenfeng

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object SettingsManager {
    private const val PREFS_NAME = "LlamaChatSettings"
    private const val KEY_MODEL_PATH = "model_path"
    private const val KEY_THREADS = "threads"
    private const val KEY_CONTEXT_SIZE = "context_size"
    private const val KEY_USE_GPU = "use_gpu"
    private const val KEY_LAST_THREADS = "last_threads"
    private const val KEY_LAST_CONTEXT_SIZE = "last_context_size"
    private const val KEY_LAST_USE_GPU = "last_use_gpu"
    private const val KEY_LAST_USED_TIME = "last_used_time"

    private const val DEFAULT_THREADS = 4
    private const val DEFAULT_CONTEXT_SIZE = 2048
    private const val DEFAULT_USE_GPU = false

    private lateinit var prefs: SharedPreferences

    private var currentThreads: Int = DEFAULT_THREADS
    private var currentContextSize: Int = DEFAULT_CONTEXT_SIZE
    private var currentUseGpu: Boolean = DEFAULT_USE_GPU

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThreads = prefs.getInt(KEY_THREADS, DEFAULT_THREADS)
        currentContextSize = prefs.getInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE)
        currentUseGpu = prefs.getBoolean(KEY_USE_GPU, DEFAULT_USE_GPU)
    }

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_MODEL_PATH, value).apply()
        }

    var threads: Int
        get() = currentThreads
        set(value) {
            val newValue = value.coerceIn(1, 8)
            if (currentThreads != newValue) {
                prefs.edit()
                    .putInt(KEY_THREADS, newValue)
                    .putInt(KEY_LAST_THREADS, currentThreads)
                    .apply()
                currentThreads = newValue
            }
        }

    var contextSize: Int
        get() = currentContextSize
        set(value) {
            if (currentContextSize != value) {
                prefs.edit()
                    .putInt(KEY_CONTEXT_SIZE, value)
                    .putInt(KEY_LAST_CONTEXT_SIZE, currentContextSize)
                    .apply()
                currentContextSize = value
            }
        }

    var useGpu: Boolean
        get() = currentUseGpu
        set(value) {
            if (currentUseGpu != value) {
                prefs.edit()
                    .putBoolean(KEY_USE_GPU, value)
                    .putBoolean(KEY_LAST_USE_GPU, currentUseGpu)
                    .apply()
                currentUseGpu = value
            }
        }

    fun hasConfigChanged(): Boolean {
        val lastThreads = prefs.getInt(KEY_LAST_THREADS, currentThreads)
        val lastContextSize = prefs.getInt(KEY_LAST_CONTEXT_SIZE, currentContextSize)
        val lastUseGpu = prefs.getBoolean(KEY_LAST_USE_GPU, currentUseGpu)

        return lastThreads != currentThreads ||
               lastContextSize != currentContextSize ||
               lastUseGpu != currentUseGpu
    }

    fun acknowledgeConfigChange() {
        prefs.edit()
            .putInt(KEY_LAST_THREADS, currentThreads)
            .putInt(KEY_LAST_CONTEXT_SIZE, currentContextSize)
            .putBoolean(KEY_LAST_USE_GPU, currentUseGpu)
            .apply()
    }

    fun getLastThreads(): Int = prefs.getInt(KEY_LAST_THREADS, currentThreads)
    fun getLastContextSize(): Int = prefs.getInt(KEY_LAST_CONTEXT_SIZE, currentContextSize)
    fun getLastUseGpu(): Boolean = prefs.getBoolean(KEY_LAST_USE_GPU, currentUseGpu)

    var lastUsedTime: Long
        get() = prefs.getLong(KEY_LAST_USED_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_USED_TIME, value).apply()
        }

    fun getFormattedLastUsedTime(): String {
        val time = lastUsedTime
        if (time == 0L) return "从未"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(time))
    }

    fun getRealPathFromURI(context: Context, uri: Uri): String? {
        try {
            val contentResolver = context.contentResolver

            val scheme = uri.scheme
            if (scheme == "file") {
                return uri.path
            }

            if (scheme == "content") {
                var cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val fileName = if (nameIndex >= 0) it.getString(nameIndex) else "model.gguf"

                        val cacheDir = File(context.cacheDir, "models")
                        if (!cacheDir.exists()) {
                            cacheDir.mkdirs()
                        }

                        val destFile = File(cacheDir, fileName)

                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        modelPath = destFile.absolutePath
                        return destFile.absolutePath
                    }
                }
            }

            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        currentThreads = DEFAULT_THREADS
        currentContextSize = DEFAULT_CONTEXT_SIZE
        currentUseGpu = DEFAULT_USE_GPU
    }
}
