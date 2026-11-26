package com.baytale.baychat.core.network

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.CreatePlaneRequest
import com.baytale.baychat.core.model.CreateRoomRequest
import com.baytale.baychat.core.model.FileUploadResponse
import com.baytale.baychat.core.model.MediaUploadResponse
import com.baytale.baychat.core.model.PaperPlane
import com.baytale.baychat.core.model.PlaneJoinResult
import com.baytale.baychat.core.model.PlaneListResponse
import com.baytale.baychat.core.model.PlaneReportRequest
import com.baytale.baychat.core.model.PlaneReportResponse
import com.baytale.baychat.core.model.PublicRoomsResponse
import com.baytale.baychat.core.model.RandomRoomRequest
import com.baytale.baychat.core.model.ReportRequest
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.ServerConfig
import com.baytale.baychat.core.model.VerifyPasswordRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
) : Exception(message)

// BayChat REST API 客户端（支持动态切换自建服务器地址，默认指向 https://chat.baytale.com）
class BayChatApi(
    private val serverUrlProvider: () -> String,
    val okHttpClient: OkHttpClient = defaultHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = serverUrlProvider().trim().trimEnd('/')

    private fun encodePath(seg: String): String = URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    // B22：节点连通性与延迟测试（请求 /api/health 并返回耗时毫秒）
    suspend fun measureServerHealth(serverUrl: String): Long = withContext(Dispatchers.IO) {
        val normalized = serverUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", ignoreCase = true) || normalized.startsWith("http://", ignoreCase = true)) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
        val req = Request.Builder()
            .url("$normalized/api/health")
            .get()
            .build()
        val startNs = System.nanoTime()
        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "HEALTH_FAILED", "节点响应异常 (HTTP ${resp.code})")
            }
            val status = runCatching {
                json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!status.equals("ok", ignoreCase = true)) {
                throw ApiException(resp.code, "INVALID_HEALTH", "节点返回值不符合 BayChat 协议")
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            elapsedMs
        }
    }

    suspend fun fetchServerConfig(clientId: String): ServerConfig = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/config?clientId=${encodePath(clientId)}")
            .get()
            .build()
        executeJson(req)
    }

    suspend fun createRoom(request: CreateRoomRequest): RoomSummary = withContext(Dispatchers.IO) {
        val body = json.encodeToString(CreateRoomRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun fetchPublicRooms(): PublicRoomsResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/public")
            .get()
            .build()
        executeJson(req)
    }

    suspend fun pickRandomPublicRoom(excludeCode: String = ""): RoomSummary = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RandomRoomRequest.serializer(), RandomRoomRequest(excludeCode))
            .toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/random")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun fetchLatestRelease(): AppReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/apk/version.json?t=${System.currentTimeMillis()}")
            .get()
            .build()
        executeJson(req)
    }

    suspend fun fetchRoomInfo(code: String): RoomSummary = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}")
            .get()
            .build()
        executeJson(req)
    }

    suspend fun verifyRoomPassword(code: String, password: String): RoomSummary = withContext(Dispatchers.IO) {
        val body = json.encodeToString(VerifyPasswordRequest.serializer(), VerifyPasswordRequest(password))
            .toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}/verify")
            .post(body)
            .build()
        executeJson(req)
    }

    // B5：房主管理 REST 接口统一附带 X-Owner-Token（与 X-Room-Password）
    suspend fun deleteRoom(
        code: String,
        password: String = "",
        ownerToken: String = "",
        actorName: String = "",
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}")
            .delete()
        if (password.isNotBlank()) builder.header("X-Room-Password", password)
        if (ownerToken.isNotBlank()) builder.header("X-Owner-Token", ownerToken)
        if (actorName.isNotBlank()) builder.header("X-Actor-Name", encodePath(actorName))
        executeRaw(builder.build())
    }

    suspend fun clearRoomHistory(
        code: String,
        password: String = "",
        ownerToken: String = "",
        actorName: String = "",
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}/history")
            .delete()
        if (password.isNotBlank()) builder.header("X-Room-Password", password)
        if (ownerToken.isNotBlank()) builder.header("X-Owner-Token", ownerToken)
        if (actorName.isNotBlank()) builder.header("X-Actor-Name", encodePath(actorName))
        executeRaw(builder.build())
    }

    suspend fun updateRoomProfile(
        code: String,
        password: String = "",
        ownerToken: String = "",
        topic: String? = null,
        newPassword: String? = null,
        actorName: String = "",
    ): RoomSummary = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            if (topic != null) put("topic", topic)
            if (newPassword != null) put("password", newPassword)
            if (actorName.isNotBlank()) put("actorName", actorName)
        }
        val builder = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}/profile")
            .put(obj.toString().toRequestBody(jsonMediaType))
        if (password.isNotBlank()) builder.header("X-Room-Password", password)
        if (ownerToken.isNotBlank()) builder.header("X-Owner-Token", ownerToken)
        executeJson(builder.build())
    }

    // G1：举报违规成员/消息（POST /api/report）
    suspend fun reportPeer(request: ReportRequest) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ReportRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/report")
            .post(body)
            .build()
        executeRaw(req)
    }

    // 纸飞机公开文件清单接口
    suspend fun fetchPlanes(limit: Int = 24): PlaneListResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/planes?limit=$limit")
            .get()
            .build()
        executeJson(req)
    }

    suspend fun createPlane(request: CreatePlaneRequest): PaperPlane = withContext(Dispatchers.IO) {
        val body = json.encodeToString(CreatePlaneRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/planes")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun joinPlane(planeId: String): PlaneJoinResult = withContext(Dispatchers.IO) {
        val emptyBody = ByteArray(0).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/planes/${encodePath(planeId)}/join")
            .post(emptyBody)
            .build()
        executeJson(req)
    }

    suspend fun reportPlane(planeId: String, clientId: String): PlaneReportResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PlaneReportRequest.serializer(), PlaneReportRequest(clientId))
            .toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/planes/${encodePath(planeId)}/report")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun uploadCachedMedia(
        code: String,
        password: String,
        bytes: ByteArray,
        mimeType: String,
    ): MediaUploadResponse = withContext(Dispatchers.IO) {
        val mediaType = (mimeType.ifBlank { "image/jpeg" }).toMediaType()
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}/media")
            .header("X-Room-Password", password)
            .post(bytes.toRequestBody(mediaType))
            .build()
        executeJson(req)
    }

    // B19：绝不将房间密码拼入 URL，鉴权统一通过 X-Room-Password 请求头传递
    fun buildCachedMediaUrl(code: String, mediaId: String): String =
        "${baseUrl()}/api/rooms/${encodePath(code)}/media/${encodePath(mediaId)}"

    @Deprecated("禁止把密码拼入 URL，请改用 buildCachedMediaUrl(code, mediaId) + imageHeaders()")
    fun buildCachedMediaUrl(code: String, mediaId: String, @Suppress("UNUSED_PARAMETER") password: String): String =
        buildCachedMediaUrl(code, mediaId)

    // 流式上传普通历史附件至房间服务器限时缓存（零内存爆炸，支持最大 historyMaxFileBytes）
    suspend fun uploadCachedFile(
        code: String,
        password: String,
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ): FileUploadResponse = withContext(Dispatchers.IO) {
        val mediaType = (mimeType.ifBlank { "application/octet-stream" }).toMediaType()
        val streamingBody = object : RequestBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = if (fileSize > 0L) fileSize else -1L
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: throw IllegalStateException("无法读取所选附件")
            }
        }
        val req = Request.Builder()
            .url("${baseUrl()}/api/rooms/${encodePath(code)}/files")
            .header("X-Room-Password", password)
            .header("X-File-Name", encodePath(fileName))
            .post(streamingBody)
            .build()
        executeJson(req)
    }

    // B19：绝不将房间密码拼入 URL
    fun buildCachedFileUrl(code: String, fileId: String): String =
        "${baseUrl()}/api/rooms/${encodePath(code)}/files/${encodePath(fileId)}"

    @Deprecated("禁止把密码拼入 URL，请改用 buildCachedFileUrl(code, fileId) + X-Room-Password 请求头")
    fun buildCachedFileUrl(code: String, fileId: String, @Suppress("UNUSED_PARAMETER") password: String): String =
        buildCachedFileUrl(code, fileId)

    // B19：流式下载历史缓存附件，支持 HTTP Range 断点续传与 X-Room-Password 请求头鉴权
    suspend fun downloadCachedFileToTemp(
        url: String,
        destFile: File,
        password: String = "",
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val existingBytes = if (destFile.exists() && destFile.isFile) destFile.length().coerceAtLeast(0L) else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .get()
        if (password.isNotBlank()) {
            reqBuilder.header("X-Room-Password", password)
        }
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 416 && existingBytes > 0L) {
                val rangeHdr = resp.header("Content-Range")
                val totalFrom416 = ResumableApkDownloader.parseTotalFrom416ContentRange(rangeHdr)
                if (totalFrom416 != null && totalFrom416 == existingBytes) {
                    onProgress(existingBytes, totalFrom416)
                    return@withContext existingBytes
                }
                // 断点无效时重置并重下
                runCatching { destFile.delete() }
                return@withContext downloadCachedFileToTemp(url, destFile, password, onProgress)
            }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "DOWNLOAD_FAILED", "附件下载失败 (${resp.code})，可能已过期或被清理")
            }
            val body = resp.body ?: throw ApiException(500, "EMPTY_BODY", "服务器未返回附件数据")
            val rangeInfo = if (resp.code == 206) {
                ResumableApkDownloader.parseContentRange(resp.header("Content-Range"))
            } else {
                null
            }
            val append = resp.code == 206 && existingBytes > 0L && (rangeInfo == null || rangeInfo.start == existingBytes)
            var downloaded = if (append) existingBytes else 0L
            val bodyLen = body.contentLength()
            val total = when {
                rangeInfo?.total != null && rangeInfo.total > 0L -> rangeInfo.total
                bodyLen > 0L -> downloaded + bodyLen
                else -> -1L
            }
            var lastReport = 0L
            body.byteStream().use { input ->
                FileOutputStream(destFile, append).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 150L || (total > 0L && downloaded >= total)) {
                            onProgress(downloaded, total)
                            lastReport = now
                        }
                    }
                    out.flush()
                }
            }
            downloaded
        }
    }

    // 调用自托管中文语音识别服务将录音转写为文字
    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val mime = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }
        val req = Request.Builder()
            .url("${baseUrl()}/api/asr")
            .post(audioFile.asRequestBody(mime.toMediaType()))
            .build()
        val raw = executeRaw(req)
        val root = json.parseToJsonElement(raw).jsonObject
        root["text"]?.jsonPrimitive?.content?.trim().orEmpty()
    }

    fun buildInviteUrl(code: String): String = "${baseUrl()}/?room=${encodePath(code)}"

    fun buildWebSocketUrl(): String {
        val url = baseUrl()
        val wsBase = when {
            url.startsWith("https://", ignoreCase = true) -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://", ignoreCase = true) -> "ws://" + url.removePrefix("http://")
            else -> "wss://$url"
        }
        return "$wsBase/ws"
    }

    private fun executeRaw(request: Request): String {
        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, text)
            }
            return text
        }
    }

    private inline fun <reified T> executeJson(request: Request): T {
        val text = executeRaw(request)
        return json.decodeFromString(text)
    }

    private fun parseApiError(statusCode: Int, text: String): ApiException {
        var code = "REQUEST_FAILED"
        var msg = "请求失败 ($statusCode)"
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            obj["errorCode"]?.jsonPrimitive?.content?.let { code = it }
            obj["error"]?.jsonPrimitive?.content?.let { msg = it }
        }
        return ApiException(statusCode, code, msg)
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(12, TimeUnit.SECONDS)
            .build()
    }
}
