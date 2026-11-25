package com.baytale.baychat.core.storage

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.baytale.baychat.core.util.TransferResumeUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class UriFileMeta(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
)

data class SavedMediaResult(
    val contentUri: Uri?,
    val displayPath: String,
    val actualFileName: String,
    val previewPath: String?,
) {
    // 兼容旧版字段名
    val previewFilePath: String? get() = previewPath
}

typealias SavedFileResult = SavedMediaResult

// Android 原生媒体/文件安全落盘、EXIF 纠偏压缩、打开与分享工具
object MediaDownloadHelper {
    private const val SUBDIR = "BayChat"
    private const val THIRTY_DAYS_MS = 30L * 24L * 3600L * 1000L

    fun queryUriMeta(context: Context, uri: Uri): UriFileMeta {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L
        val resolver = context.contentResolver
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                name = file.name
                size = file.length()
            }
        } else {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                        }
                    }
            }
            if (size < 0L) {
                runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length >= 0L) size = afd.length
                    }
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

    // 流式计算本地 Uri 的跨端断点续传 resumeKey（优先 FileChannel O(1) seek，退化为流式环形缓冲）
    fun computeResumeKey(context: Context, uri: Uri, fileName: String, fileSize: Long): String {
        if (fileSize >= TransferResumeUtils.TWO_MIB) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        val channel = fis.channel
                        val channelSize = channel.size()
                        if (channelSize >= TransferResumeUtils.TWO_MIB) {
                            val head = ByteArray(TransferResumeUtils.ONE_MIB)
                            val tail = ByteArray(TransferResumeUtils.ONE_MIB)
                            channel.position(0L)
                            readChannelFully(channel, head)
                            channel.position(channelSize - TransferResumeUtils.ONE_MIB)
                            readChannelFully(channel, tail)
                            return TransferResumeUtils.computeResumeKey(fileName, channelSize, head, tail)
                        }
                    }
                }
            }
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            TransferResumeUtils.computeResumeKeyFromStream(fileName, fileSize, input)
        } ?: TransferResumeUtils.computeResumeKey(fileName, fileSize, ByteArray(0))
    }

    private fun readChannelFully(channel: java.nio.channels.FileChannel, target: ByteArray) {
        val buf = ByteBuffer.wrap(target)
        while (buf.hasRemaining()) {
            val r = channel.read(buf)
            if (r <= 0) break
        }
    }

    // 按媒体类型分目录落盘：图片 -> Pictures/BayChat，视频 -> Movies/BayChat，其余 -> Download/BayChat
    fun saveReceivedFile(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        keepLocalPreviewCopy: Boolean,
        isExplicitAudio: Boolean = false,
    ): SavedMediaResult {
        val safeName = sanitizeFileName(fileName)
        val safeMime = mimeType.ifBlank { "application/octet-stream" }

        var previewPath: String? = null
        if (keepLocalPreviewCopy) {
            val previewDir = File(context.filesDir, "received_media").apply { mkdirs() }
            cleanupOldReceivedMedia(previewDir)
            val previewFile = File(previewDir, "${System.currentTimeMillis()}_$safeName")
            sourceFile.copyTo(previewFile, overwrite = true)
            previewPath = previewFile.absolutePath
        }

        val isImage = TransferResumeUtils.isTrustedImage(safeName, safeMime)
        val isVideo = !isImage && TransferResumeUtils.isVideoMedia(safeName, safeMime, isExplicitAudio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collectionUri, publicDir, displayFolder) = when {
                isImage -> Triple(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_PICTURES,
                    "Pictures/$SUBDIR",
                )
                isVideo -> Triple(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_MOVIES,
                    "Movies/$SUBDIR",
                )
                else -> Triple(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Download/$SUBDIR",
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$SUBDIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = runCatching { resolver.insert(collectionUri, values) }.getOrNull()
            if (uri != null) {
                runCatching {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out, 64 * 1024) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }.onSuccess {
                    sourceFile.delete()
                    val actualName = queryMediaStoreDisplayName(context, uri) ?: safeName
                    return SavedMediaResult(
                        contentUri = uri,
                        displayPath = "$displayFolder/$actualName",
                        actualFileName = actualName,
                        previewPath = previewPath,
                    )
                }.onFailure {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }

        // Android 8~9（API 26~28）优先写入系统公共目录，无权限时自动降级到应用外部目录
        val publicType = when {
            isImage -> Environment.DIRECTORY_PICTURES
            isVideo -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val publicDisplayPrefix = when {
            isImage -> "Pictures/$SUBDIR"
            isVideo -> "Movies/$SUBDIR"
            else -> "Download/$SUBDIR"
        }

        val target = writeToPublicOrAppExternal(context, sourceFile, safeName, publicType)
        sourceFile.delete()
        if (isImage || isVideo) {
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(safeMime), null)
            }
        }
        val providerUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
        return SavedMediaResult(
            contentUri = providerUri,
            displayPath = "$publicDisplayPrefix/${target.name}",
            actualFileName = target.name,
            previewPath = previewPath ?: target.absolutePath,
        )
    }

    @Suppress("DEPRECATION")
    private fun writeToPublicOrAppExternal(
        context: Context,
        sourceFile: File,
        safeName: String,
        envType: String,
    ): File {
        val publicDir = runCatching {
            File(Environment.getExternalStoragePublicDirectory(envType), SUBDIR).apply { mkdirs() }
        }.getOrNull()?.takeIf { it.exists() && it.canWrite() }

        val dir = publicDir ?: File(
            context.getExternalFilesDir(envType) ?: context.filesDir,
            SUBDIR
        ).apply { mkdirs() }

        val target = uniqueFile(dir, safeName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
        }
        return target
    }

    private fun queryMediaStoreDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    // 导出聊天记录文本到系统 Downloads/BayChat/（返回显示路径，兼容既有调用方）
    fun saveTextToDownloads(context: Context, fileName: String, content: String): String {
        return saveTextToDownloadsResult(context, fileName, content).displayPath
    }

    // 导出聊天记录并返回完整 SavedMediaResult（含可直接打开/分享的 contentUri）
    fun saveTextToDownloadsResult(context: Context, fileName: String, content: String): SavedMediaResult {
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
        tmp.writeText(content, Charsets.UTF_8)
        return saveReceivedFile(context, tmp, fileName, "text/plain", keepLocalPreviewCopy = false)
    }

    // B18：带 EXIF 旋转/镜像纠偏的图片压缩（兼容 HEIC/HEIF 强制转 JPEG）
    fun compressImageToJpeg(context: Context, uri: Uri, maxEdgePx: Int = 1600, quality: Int = 82): ByteArray? {
        return runCatching {
            val orientation = readExifOrientation(context, uri)
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

            val rotated = applyExifOrientation(rawBitmap, orientation)
            val maxDim = maxOf(rotated.width, rotated.height)
            val scaled = if (maxDim > maxEdgePx) {
                val scale = maxEdgePx.toFloat() / maxDim.toFloat()
                Bitmap.createScaledBitmap(
                    rotated,
                    (rotated.width * scale).toInt().coerceAtLeast(1),
                    (rotated.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== rotated) rotated.recycle() }
            } else {
                rotated
            }

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                scaled.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    // 保存图片副本到系统相册（Pictures/BayChat）
    fun saveImageToGallery(
        context: Context,
        sourcePathOrUri: String,
        fileName: String,
        mimeType: String = "image/jpeg",
    ): SavedMediaResult? {
        return runCatching {
            val tmp = File(context.cacheDir, "gallery_${System.currentTimeMillis()}_${sanitizeFileName(fileName)}")
            if (sourcePathOrUri.startsWith("content://") || sourcePathOrUri.startsWith("file://")) {
                val uri = Uri.parse(sourcePathOrUri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
                } ?: return null
            } else {
                val src = File(sourcePathOrUri)
                if (!src.exists()) return null
                src.copyTo(tmp, overwrite = true)
            }
            val effectiveMime = if (mimeType.startsWith("image/")) mimeType else "image/jpeg"
            val safeName = if (TransferResumeUtils.isTrustedImage(fileName, effectiveMime)) {
                fileName
            } else {
                "${fileName.substringBeforeLast('.')}.jpg"
            }
            saveReceivedFile(
                context = context,
                sourceFile = tmp,
                fileName = safeName,
                mimeType = effectiveMime,
                keepLocalPreviewCopy = false,
            )
        }.getOrNull()
    }

    // 文件打开前探测结果：区分成功打开、本地文件丢失或无可用打开应用
    enum class OpenFileResult {
        OPENED,
        FILE_NOT_FOUND,
        NO_APP_FOUND,
    }

    // 安全打开本地路径或 content:// URI（返回详细状态以支持精确失败提示）
    fun openLocalFileDetailed(context: Context, localPath: String, mimeType: String): OpenFileResult {
        if (localPath.startsWith("content://") || localPath.startsWith("file://")) {
            return openContentUriDetailed(context, localPath, mimeType)
        }
        val file = File(localPath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return OpenFileResult.FILE_NOT_FOUND
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return OpenFileResult.FILE_NOT_FOUND
        return launchViewIntent(context, uri, mimeType)
    }

    fun openContentUriDetailed(context: Context, uriString: String, mimeType: String): OpenFileResult {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return OpenFileResult.FILE_NOT_FOUND
        if (uri.scheme == "file" && uri.path != null) {
            return openLocalFileDetailed(context, uri.path!!, mimeType)
        }
        val readable = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) {
            return OpenFileResult.FILE_NOT_FOUND
        }
        return launchViewIntent(context, uri, mimeType)
    }

    private fun launchViewIntent(context: Context, uri: Uri, mimeType: String): OpenFileResult {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return OpenFileResult.NO_APP_FOUND
        return if (runCatching {
                context.startActivity(Intent.createChooser(intent, "打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        ) {
            OpenFileResult.OPENED
        } else {
            OpenFileResult.NO_APP_FOUND
        }
    }

    // 安全打开本地路径或 content:// URI
    fun openLocalFile(context: Context, localPath: String, mimeType: String): Boolean {
        return openLocalFileDetailed(context, localPath, mimeType) == OpenFileResult.OPENED
    }

    fun openContentUri(context: Context, uriString: String, mimeType: String): Boolean {
        return openContentUriDetailed(context, uriString, mimeType) == OpenFileResult.OPENED
    }

    fun shareUriOrPath(context: Context, uriOrPath: String, mimeType: String, title: String = "分享文件"): Boolean {
        val shareUri = resolveShareableUri(context, uriOrPath) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun resolveShareableUri(context: Context, uriOrPath: String): Uri? {
        if (uriOrPath.isBlank()) return null
        if (uriOrPath.startsWith("content://")) {
            return runCatching { Uri.parse(uriOrPath) }.getOrNull()
        }
        val file = if (uriOrPath.startsWith("file://")) {
            Uri.parse(uriOrPath).path?.let { File(it) }
        } else {
            File(uriOrPath)
        } ?: return null
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    fun openDownloadsFolder(context: Context): Boolean {
        val candidates = listOf(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"), "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in candidates) {
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    // B24：定期清理超过 30 天的本地 received_media 预览副本，避免空间无限膨胀
    fun cleanupOldReceivedMedia(previewDir: File, nowMs: Long = System.currentTimeMillis()): Int {
        if (!previewDir.exists() || !previewDir.isDirectory) return 0
        val cutoff = nowMs - THIRTY_DAYS_MS
        var deleted = 0
        previewDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
        }
        return deleted
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
