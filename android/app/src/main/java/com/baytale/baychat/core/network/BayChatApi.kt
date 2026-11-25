package com.baytale.baychat.core.network

import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.CreateRoomRequest
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
