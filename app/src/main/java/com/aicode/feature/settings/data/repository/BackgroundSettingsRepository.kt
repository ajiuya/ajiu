package com.aicode.feature.settings.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backgroundDataStore by preferencesDataStore(
    name = "background_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 全局自定义背景图：图片文件拷贝到应用私有目录持久化（不依赖 content URI 授权），
 * 透明度与图片路径存 DataStore。路径为 null 表示未设置背景。
 */
@Singleton
class BackgroundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_ALPHA = 0.15f
        /** 顶层水印模式下透明度上限：超过会盖住文字影响可读性。 */
        const val MAX_ALPHA = 0.2f
        /** 最小透明度：0 表示完全透明（背景不可见）。 */
        const val MIN_ALPHA = 0f
        const val DEFAULT_CHAT_ALPHA = 0.2f
        const val MIN_CHAT_ALPHA = 0f
        const val MAX_CHAT_ALPHA = 1f
        private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_DECODE_DIMENSION = 2048
        private val IMAGE_PATH_KEY = stringPreferencesKey("background_image_path")
        private val ALPHA_KEY = floatPreferencesKey("background_alpha")
        private val CHAT_IMAGE_PATH_KEY = stringPreferencesKey("chat_background_image_path")
        private val CHAT_ALPHA_KEY = floatPreferencesKey("chat_background_alpha")

        internal fun clampChatAlpha(alpha: Float): Float =
            if (alpha.isNaN()) DEFAULT_CHAT_ALPHA else alpha.coerceIn(MIN_CHAT_ALPHA, MAX_CHAT_ALPHA)

        /** 把 UI 百分比（0~100）线性映射为实际透明度（0~MAX_ALPHA）。 */
        fun sliderToAlpha(percent: Float): Float =
            MAX_ALPHA * (percent / 100f).coerceIn(0f, 1f)

        /** 把实际透明度反映射为 UI 百分比（0~100）。 */
        fun alphaToSlider(alpha: Float): Float =
            (alpha / MAX_ALPHA).coerceIn(0f, 1f) * 100f
    }

    private val imageMutex = Mutex()

    /** 当前背景图文件绝对路径；null 表示未设置。 */
    val imagePathFlow: Flow<String?> = context.backgroundDataStore.data.map { it[IMAGE_PATH_KEY] }

    /** 背景图不透明度（0~0.2，顶层水印保证文字可读）。 */
    val alphaFlow: Flow<Float> = context.backgroundDataStore.data.map {
        (it[ALPHA_KEY] ?: DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)
    }

    val chatImagePathFlow: Flow<String?> = context.backgroundDataStore.data.map { it[CHAT_IMAGE_PATH_KEY] }

    val chatAlphaFlow: Flow<Float> = context.backgroundDataStore.data.map {
        clampChatAlpha(it[CHAT_ALPHA_KEY] ?: DEFAULT_CHAT_ALPHA)
    }

    suspend fun setBackgroundImage(uri: Uri) {
        replaceImage(uri, IMAGE_PATH_KEY)
    }

    suspend fun clearBackground() {
        clearImage(IMAGE_PATH_KEY)
    }

    suspend fun setChatBackgroundImage(uri: Uri) {
        replaceImage(uri, CHAT_IMAGE_PATH_KEY)
    }

    suspend fun clearChatBackground() {
        clearImage(CHAT_IMAGE_PATH_KEY)
    }

    suspend fun setChatBackgroundAlpha(alpha: Float) {
        context.backgroundDataStore.edit { it[CHAT_ALPHA_KEY] = clampChatAlpha(alpha) }
    }

    suspend fun chatAlphaSnapshot(): Float = chatAlphaFlow.first()

    suspend fun setBackgroundAlpha(alpha: Float) {
        context.backgroundDataStore.edit {
            it[ALPHA_KEY] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
        }
    }

    private suspend fun replaceImage(uri: Uri, key: Preferences.Key<String>) = withContext(Dispatchers.IO) {
        imageMutex.withLock {
            val target = copyImage(uri) ?: return@withLock
            // 路径切换与文件清理不能被取消拆开，否则可能留下指向已删除文件的设置。
            withContext(NonCancellable) {
                var saved = false
                try {
                    var oldPath: String? = null
                    context.backgroundDataStore.edit {
                        oldPath = it[key]
                        it[key] = target.absolutePath
                    }
                    saved = true
                    deleteImage(oldPath)
                } finally {
                    if (!saved) deleteImage(target.absolutePath)
                }
            }
        }
    }

    private suspend fun clearImage(key: Preferences.Key<String>) = withContext(Dispatchers.IO) {
        imageMutex.withLock {
            withContext(NonCancellable) {
                var oldPath: String? = null
                context.backgroundDataStore.edit {
                    oldPath = it[key]
                    it.remove(key)
                }
                deleteImage(oldPath)
            }
        }
    }

    private fun copyImage(uri: Uri): File? {
        var target: File? = null
        return runCatching {
            val directory = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val file = File.createTempFile("bg_", ".${extensionFor(uri)}", directory)
            target = file
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open background image")
            input.use {
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_IMAGE_BYTES) throw IOException("Background image is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!isDecodableImage(file)) throw IOException("Invalid background image")
            file
        }.getOrElse {
            deleteImage(target?.absolutePath)
            null
        }
    }

    private fun isDecodableImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType?.startsWith("image/") != true) {
            return false
        }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        bitmap.recycle()
        return true
    }

    private fun deleteImage(path: String?) {
        path?.let { runCatching { File(it).delete() } }
    }

    private fun extensionFor(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
