package com.baytale.baychat.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class UriFileMeta(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
)

data class SavedFileResult(
    val displayPath: String,
    val previewFilePath: String?,
    val contentUri: Uri?,
)

// Android 原生文件读写与公共下载目录写入工具（Android 10+ 零存储权限直写 Downloads/BayChat/）
object MediaDownloadHelper {
    private const val DOWNLOAD_SUBDIR = "BayChat"

    fun queryUriMeta(context: Context, uri: Uri): UriFileMeta {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        if (size < 0L) {
            runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length >= 0L) size = afd.length
                }
            }
        }
        val detectedMime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return UriFileMeta(
            uri = uri,
            fileName = sanitizeFileName(name),
            fileSize = size.coerceAtLeast(0L),
            mimeType = detectedMime,
        )
    }

    // 流式将收到的临时文件写入系统 Downloads/BayChat/，不把大文件载入内存
    fun saveReceivedFile(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        keepLocalPreviewCopy: Boolean,
    ): SavedFileResult {
        val safeName = sanitizeFileName(fileName)
        val safeMime = mimeType.ifBlank { "application/octet-stream" }

        var previewPath: String? = null
        if (keepLocalPreviewCopy) {
            val previewDir = File(context.filesDir, "received_media").apply { mkdirs() }
            val previewFile = File(previewDir, "${System.currentTimeMillis()}_$safeName")
            sourceFile.copyTo(previewFile, overwrite = true)
            previewPath = previewFile.absolutePath
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, safeMime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out, 64 * 1024) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                sourceFile.delete()
                return SavedFileResult(
                    displayPath = "Download/$DOWNLOAD_SUBDIR/$safeName",
                    previewFilePath = previewPath,
                    contentUri = uri,
                )
            }
        }

        // Android 8~9 或降级路径：写入应用外部下载目录
        val fallbackDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            DOWNLOAD_SUBDIR
        ).apply { mkdirs() }
        val target = uniqueFile(fallbackDir, safeName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
        }
        sourceFile.delete()
        return SavedFileResult(
            displayPath = target.absolutePath,
            previewFilePath = previewPath ?: target.absolutePath,
            contentUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            }.getOrNull(),
        )
    }

    // 导出聊天记录文本到系统 Downloads/BayChat/
    fun saveTextToDownloads(context: Context, fileName: String, content: String): String {
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
        tmp.writeText(content, Charsets.UTF_8)
        val result = saveReceivedFile(context, tmp, fileName, "text/plain", keepLocalPreviewCopy = false)
        return result.displayPath
    }

    // 轻量化压缩图片用于服务器缓存或省流发送（防止 OOM）
    fun compressImageToJpeg(context: Context, uri: Uri, maxEdgePx: Int = 1600, quality: Int = 82): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            var sample = 1
            while (w / sample > maxEdgePx * 2 || h / sample > maxEdgePx * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val maxDim = maxOf(rawBitmap.width, rawBitmap.height)
            val scaled = if (maxDim > maxEdgePx) {
                val scale = maxEdgePx.toFloat() / maxDim.toFloat()
                Bitmap.createScaledBitmap(
                    rawBitmap,
                    (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                    (rawBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== rawBitmap) rawBitmap.recycle() }
            } else {
                rawBitmap
            }

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                scaled.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    // 使用系统默认应用安全打开本地接收的文件
    fun openLocalFile(context: Context, localPath: String, mimeType: String): Boolean {
        val file = File(localPath)
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        return cleaned.ifBlank { "baychat_${System.currentTimeMillis()}" }.take(120)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_$idx$ext")
            idx++
        }
        return candidate
    }
}
