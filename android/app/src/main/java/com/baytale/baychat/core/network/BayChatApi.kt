package com.baytale.baychat.core.network

import android.content.Context
import android.net.Uri
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.CreateRoomRequest
import com.baytale.baychat.core.model.FileUploadResponse
import com.baytale.baychat.core.model.MediaUploadResponse
import com.baytale.baychat.core.model.PublicRoomsResponse
import com.baytale.baychat.core.model.RandomRoomRequest
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.ServerConfig
import com.baytale.baychat.core.model.VerifyPasswordRequest
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
        encodeDefaults = false
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = serverUrlProvider().trim().trimEnd('/')

    private fun encodePath(seg: String): String = URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

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

    fun buildCachedMediaUrl(code: String, mediaId: String, password: String): String {
        val base = "${baseUrl()}/api/rooms/${encodePath(code)}/media/${encodePath(mediaId)}"
        return if (password.isBlank()) base else "$base?password=${encodePath(password)}"
    }

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

    fun buildCachedFileUrl(code: String, fileId: String, password: String): String {
        val base = "${baseUrl()}/api/rooms/${encodePath(code)}/files/${encodePath(fileId)}"
        return if (password.isBlank()) base else "$base?password=${encodePath(password)}"
    }

    // 流式下载历史缓存附件到本地临时文件并回调进度
    suspend fun downloadCachedFileToTemp(
        url: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "DOWNLOAD_FAILED", "附件下载失败 (${resp.code})，可能已过期或被清理")
            }
            val body = resp.body ?: throw ApiException(500, "EMPTY_BODY", "服务器未返回附件数据")
            val total = body.contentLength()
            var downloaded = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
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

    private inline fun <reified T> executeJson(request: Request): T {
        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                var code = "REQUEST_FAILED"
                var msg = "请求失败 (${resp.code})"
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    obj["errorCode"]?.jsonPrimitive?.content?.let { code = it }
                    obj["error"]?.jsonPrimitive?.content?.let { msg = it }
                }
                throw ApiException(resp.code, code, msg)
            }
            return json.decodeFromString(text)
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
