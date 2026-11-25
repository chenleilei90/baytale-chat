package com.baytale.baychat.core.network

import com.baytale.baychat.core.model.NearbyHeartbeatRequest
import com.baytale.baychat.core.model.NearbyHeartbeatResponse
import com.baytale.baychat.core.model.NearbyInviteRequest
import com.baytale.baychat.core.model.NearbyInviteResponse
import com.baytale.baychat.core.model.NearbyRespondRequest
import com.baytale.baychat.core.model.NearbyRespondResponse
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

// 同局域网 / 同出口 IP 附近设备发现与邀请 REST 客户端（与 Go server/internal/nearby/nearby.go 100% 对齐）
class NearbyApi(
    private val serverUrlProvider: () -> String,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = serverUrlProvider().trim().trimEnd('/')

    private fun encodePath(seg: String): String = URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    suspend fun heartbeat(request: NearbyHeartbeatRequest): NearbyHeartbeatResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(NearbyHeartbeatRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/nearby/heartbeat")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun sendInvite(request: NearbyInviteRequest): NearbyInviteResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(NearbyInviteRequest.serializer(), request).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/nearby/invite")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun respondInvite(inviteId: String, clientId: String, accept: Boolean): NearbyRespondResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(NearbyRespondRequest.serializer(), NearbyRespondRequest(clientId, accept))
            .toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${baseUrl()}/api/nearby/invite/${encodePath(inviteId)}/respond")
            .post(body)
            .build()
        executeJson(req)
    }

    suspend fun getInviteStatus(inviteId: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/nearby/invite/${encodePath(inviteId)}")
            .get()
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseApiError(resp.code, text)
            json.parseToJsonElement(text).jsonObject["status"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    private inline fun <reified T> executeJson(request: Request): T {
        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseApiError(resp.code, text)
            return json.decodeFromString(text)
        }
    }

    private fun parseApiError(statusCode: Int, text: String): ApiException {
        var code = "NEARBY_ERROR"
        var msg = "附近设备请求失败 ($statusCode)"
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            obj["errorCode"]?.jsonPrimitive?.content?.let { code = it }
            obj["error"]?.jsonPrimitive?.content?.let { msg = it }
        }
        return ApiException(statusCode, code, msg)
    }
}
