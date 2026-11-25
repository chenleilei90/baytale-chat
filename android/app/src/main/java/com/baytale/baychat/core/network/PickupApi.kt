package com.baytale.baychat.core.network

import android.content.Context
import android.net.Uri
import com.baytale.baychat.core.model.PickupCreateFileInput
import com.baytale.baychat.core.model.PickupCreateInitRequest
import com.baytale.baychat.core.model.PickupCreateResponse
import com.baytale.baychat.core.model.PickupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

// 离线取件码 REST 客户端（与 Go server/main.go 420-496 及 pickup.go 100% 字节对齐）
class PickupApi(
    private val serverUrlProvider: () -> String,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = serverUrlProvider().trim().trimEnd('/')

    private fun encodePath(seg: String): String = URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    // 初始化取件码 -> HEAD /api/pickups/{code}/files/{fileId} 探测 X-Uploaded-Bytes -> PUT 断点流式上传
    suspend fun uploadPickupFromUri(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        expireHours: Int = 24,
        burnAfterRead: Boolean = false,
        password: String = "",
        existingCode: String? = null,
        existingOwnerToken: String? = null,
        existingFileId: String? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long, code: String) -> Unit = { _, _, _ -> },
    ): PickupCreateResponse = withContext(Dispatchers.IO) {
        val validExpire = when (expireHours) {
            1, 24, 72 -> expireHours
            else -> 24
        }
        val initResp = if (!existingCode.isNullOrBlank() && !existingOwnerToken.isNullOrBlank() && !existingFileId.isNullOrBlank()) {
            PickupCreateResponse(
                code = existingCode,
                ownerToken = existingOwnerToken,
                files = listOf(
                    com.baytale.baychat.core.model.PickupFileInfo(
                        fileId = existingFileId,
                        name = fileName,
                        size = fileSize,
                        mime = mimeType,
                    )
                ),
            )
        } else {
            initPickupSession(
                PickupCreateInitRequest(
                    files = listOf(
                        PickupCreateFileInput(
                            name = fileName,
                            size = fileSize,
                            mime = mimeType.ifBlank { "application/octet-stream" },
                        )
                    ),
                    expireHours = validExpire,
                    burnAfterRead = burnAfterRead,
                    password = password.trim().takeIf { it.isNotBlank() },
                )
            )
        }

        val code = initResp.code
        val token = initResp.ownerToken
        val fileId = initResp.files.firstOrNull()?.fileId
            ?: throw ApiException(500, "MISSING_FILE_ID", "服务端未返回文件标识")

        val uploadOffset = probeUploadOffset(code, fileId, token).coerceIn(0L, fileSize.coerceAtLeast(0L))
        if (fileSize > 0L && uploadOffset >= fileSize) {
            onProgress(fileSize, fileSize, code)
            return@withContext initResp
        }

        val remaining = (fileSize - uploadOffset).coerceAtLeast(0L)
        val mediaType = (mimeType.ifBlank { "application/octet-stream" }).toMediaType()
        val body = object : RequestBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = if (fileSize > 0L) remaining else -1L
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    var toSkip = uploadOffset
                    val skipBuf = ByteArray(32 * 1024)
                    while (toSkip > 0L) {
                        val skipped = input.skip(toSkip)
                        if (skipped > 0L) {
                            toSkip -= skipped
                        } else {
                            val r = input.read(skipBuf, 0, minOf(toSkip, skipBuf.size.toLong()).toInt())
                            if (r <= 0) break
                            toSkip -= r
                        }
                    }

                    val buf = ByteArray(32 * 1024)
                    var sent = uploadOffset
                    var lastReportAt = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        sent += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 150L || (fileSize > 0L && sent >= fileSize)) {
                            onProgress(sent, fileSize, code)
                            lastReportAt = now
                        }
                    }
                } ?: throw IllegalStateException("无法读取待上传文件")
            }
        }

        val putReq = Request.Builder()
            .url("${baseUrl()}/api/pickups/${encodePath(code)}/files/${encodePath(fileId)}")
            .header("X-Owner-Token", token)
            .header("X-Upload-Offset", uploadOffset.toString())
            .put(body)
            .build()

        okHttpClient.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, resp.body?.string().orEmpty())
            }
        }
        initResp
    }

    suspend fun initPickupSession(request: PickupCreateInitRequest): PickupCreateResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PickupCreateInitRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/pickups")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun probeUploadOffset(code: String, fileId: String, ownerToken: String): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/pickups/${encodePath(code)}/files/${encodePath(fileId)}")
            .header("X-Owner-Token", ownerToken)
            .head()
            .build()
        runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use 0L
                resp.header("X-Uploaded-Bytes")?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    suspend fun getPickupInfo(code: String, password: String = ""): PickupInfo = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl()}/api/pickups/${encodePath(code.trim())}")
            .get()
        if (password.isNotBlank()) {
            builder.header("X-Pickup-Password", password)
        }
        executeJson(builder.build())
    }

    // 支持 HTTP Range 断点续传下载单文件（GET /api/pickups/{code}/files/{fileId}）
    suspend fun downloadPickupFileToTemp(
        code: String,
        fileId: String,
        password: String = "",
        destFile: File,
        expectedSize: Long = 0L,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val existing = if (destFile.exists() && destFile.isFile) destFile.length().coerceAtLeast(0L) else 0L
        val url = "${baseUrl()}/api/pickups/${encodePath(code.trim())}/files/${encodePath(fileId.trim())}"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .get()
        if (password.isNotBlank()) {
            reqBuilder.header("X-Pickup-Password", password)
        }
        if (existing > 0L) {
            reqBuilder.header("Range", "bytes=$existing-")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 416 && existing > 0L) {
                val totalFrom416 = ResumableApkDownloader.parseTotalFrom416ContentRange(resp.header("Content-Range"))
                if (totalFrom416 != null && totalFrom416 == existing) {
                    onProgress(existing, totalFrom416)
                    return@withContext existing
                }
                runCatching { destFile.delete() }
                return@withContext downloadPickupFileToTemp(code, fileId, password, destFile, expectedSize, onProgress)
            }
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw parseApiError(resp.code, errBody)
            }
            val body = resp.body ?: throw ApiException(500, "EMPTY_BODY", "服务器未返回取件文件数据")
            val rangeInfo = if (resp.code == 206) {
                ResumableApkDownloader.parseContentRange(resp.header("Content-Range"))
            } else {
                null
            }
            val append = resp.code == 206 && existing > 0L && (rangeInfo == null || rangeInfo.start == existing)
            var downloaded = if (append) existing else 0L
            val bodyLen = body.contentLength()
            val total = when {
                rangeInfo?.total != null && rangeInfo.total > 0L -> rangeInfo.total
                bodyLen > 0L -> downloaded + bodyLen
                expectedSize > 0L -> expectedSize
                else -> -1L
            }
            var lastReportAt = 0L
            body.byteStream().use { input ->
                FileOutputStream(destFile, append).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 150L || (total > 0L && downloaded >= total)) {
                            onProgress(downloaded, total)
                            lastReportAt = now
                        }
                    }
                    out.flush()
                }
            }
            downloaded
        }
    }

    suspend fun revokePickup(code: String, ownerToken: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/pickups/${encodePath(code.trim())}")
            .header("X-Owner-Token", ownerToken)
            .delete()
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, resp.body?.string().orEmpty())
            }
            true
        }
    }

    private inline fun <reified T> executeJson(request: Request): T {
        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, text)
            }
            return json.decodeFromString(text)
        }
    }

    private fun parseApiError(statusCode: Int, text: String): ApiException {
        var code = "PICKUP_ERROR"
        var msg = "取件操作失败 ($statusCode)"
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            obj["errorCode"]?.jsonPrimitive?.content?.let { code = it }
            obj["error"]?.jsonPrimitive?.content?.let { msg = it }
        }
        return ApiException(statusCode, code, msg)
    }
}
