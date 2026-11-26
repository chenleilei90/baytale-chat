package com.baytale.baychat.core.lan

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.baytale.baychat.core.storage.MediaDownloadHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LanDirectPeer(
    val serviceName: String,
    val displayName: String,
    val clientId: String,
    val host: String,
    val port: Int,
    val lastSeenAt: Long = System.currentTimeMillis(),
) {
    val peerKey: String get() = clientId.ifBlank { serviceName }
}

data class LanDirectMessage(
    val id: String,
    val peerKey: String,
    val senderName: String,
    val isSelf: Boolean,
    val isFile: Boolean,
    val text: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val savedPath: String? = null,
    val verified: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 真·离线局域网 mDNS + TCP Socket 直连管理器（断外网零服务器可用）：
 * 通过系统 NsdManager 广播/发现 _baychat._tcp. 服务，建立点对点 Socket 收发文字与高带宽文件流。
 */
class LanDirectManager(
    private val context: Context,
    private val onNotice: (String) -> Unit = {},
) {
    companion object {
        const val SERVICE_TYPE = "_baychat._tcp."
        private const val MAX_MESSAGES = 80
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager: NsdManager? by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _localEndpoint = MutableStateFlow("")
    val localEndpoint: StateFlow<String> = _localEndpoint.asStateFlow()

    private val _peers = MutableStateFlow<List<LanDirectPeer>>(emptyList())
    val peers: StateFlow<List<LanDirectPeer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<LanDirectMessage>>(emptyList())
    val messages: StateFlow<List<LanDirectMessage>> = _messages.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, LanDirectPeer>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var registeredServiceName: String = ""

    @Volatile
    private var selfClientId: String = ""

    fun start(displayName: String, clientId: String) {
        if (_isRunning.value) return
        selfClientId = clientId
        val mgr = nsdManager
        if (mgr == null) {
            onNotice("当前设备不支持系统 mDNS 局域网服务")
            return
        }

        val lock = runCatching {
            wifiManager?.createMulticastLock("baychat_mdns_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        multicastLock = lock

        val server = runCatching { ServerSocket(0) }.getOrNull()
        if (server == null) {
            runCatching { multicastLock?.release() }
            multicastLock = null
            onNotice("局域网直连端口初始化失败")
            return
        }
        serverSocket = server
        val localPort = server.localPort
        val localIp = resolveLanIpv4().ifBlank { "127.0.0.1" }
        _localEndpoint.value = "$localIp:$localPort"
        _isRunning.value = true

        acceptJob = scope.launch {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleIncomingSocket(client) }
            }
        }

        registerMdnsService(mgr, displayName, clientId, localPort)
        startMdnsDiscovery(mgr)
    }

    fun stop() {
        _isRunning.value = false
        _localEndpoint.value = ""
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null

        discoveryListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        discoveryListener = null

        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null

        runCatching { multicastLock?.release() }
        multicastLock = null
        registeredServiceName = ""
        peerMap.clear()
        _peers.value = emptyList()
    }

    fun sendText(
        peer: LanDirectPeer,
        senderId: String,
        senderName: String,
        senderAvatar: String,
        text: String,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val ok = runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(peer.host, peer.port), 4_000)
                    sock.soTimeout = 12_000
                    LanDirectProtocol.writeTextFrame(
                        out = sock.getOutputStream(),
                        senderId = senderId,
                        senderName = senderName,
                        senderAvatar = senderAvatar,
                        text = clean,
                        timestamp = now,
                    )
                }
            }.isSuccess
            if (ok) {
                appendMessage(
                    LanDirectMessage(
                        id = "lan_txt_${UUID.randomUUID()}",
                        peerKey = peer.peerKey,
                        senderName = senderName,
                        isSelf = true,
                        isFile = false,
                        text = clean,
                        timestamp = now,
                    ),
                )
            } else {
                onNotice("发送给 ${peer.displayName} 失败，请确认双方在同一 Wi-Fi 下")
            }
        }
    }

    fun sendFile(
        peer: LanDirectPeer,
        uri: Uri,
        senderId: String,
        senderName: String,
        senderAvatar: String,
    ) {
        scope.launch {
            val meta = runCatching { MediaDownloadHelper.queryUriMeta(context, uri) }.getOrNull()
            if (meta == null) {
                onNotice("无法读取选中的文件")
                return@launch
            }
            val sha256 = runCatching { computeUriSha256(uri) }.getOrDefault("")
            val now = System.currentTimeMillis()
            val ok = runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(peer.host, peer.port), 5_000)
                    sock.soTimeout = 60_000
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        LanDirectProtocol.writeFileFrame(
                            out = sock.getOutputStream(),
                            senderId = senderId,
                            senderName = senderName,
                            senderAvatar = senderAvatar,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize.coerceAtLeast(0L),
                            mimeType = meta.mimeType,
                            sha256 = sha256,
                            fileStream = stream,
                        )
                    } ?: error("无法打开文件流")
                }
            }.isSuccess

            if (ok) {
                appendMessage(
                    LanDirectMessage(
                        id = "lan_file_${UUID.randomUUID()}",
                        peerKey = peer.peerKey,
                        senderName = senderName,
                        isSelf = true,
                        isFile = true,
                        fileName = meta.fileName,
                        fileSize = meta.fileSize,
                        verified = sha256.isNotBlank(),
                        timestamp = now,
                    ),
                )
                onNotice("已通过局域网直传发出 ${meta.fileName}")
            } else {
                onNotice("局域网直传 ${meta.fileName} 失败")
            }
        }
    }

    private fun handleIncomingSocket(socket: Socket) {
        socket.use { sock ->
            runCatching {
                sock.soTimeout = 60_000
                var tmpFile: File? = null
                val frame = LanDirectProtocol.readFrame(
                    input = sock.getInputStream(),
                    fileOutputSinkProvider = { header ->
                        val dir = File(context.cacheDir, "lan_tmp").apply { mkdirs() }
                        val safe = header.fileName.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
                            .ifBlank { "lan_file_${System.currentTimeMillis()}" }
                        val f = File(dir, "${System.currentTimeMillis()}_$safe")
                        tmpFile = f
                        FileOutputStream(f)
                    },
                ) ?: return@runCatching

                when (frame) {
                    is LanDirectProtocol.ReceivedFrame.TextFrame -> {
                        val h = frame.header
                        val senderKey = h.senderId.ifBlank { h.senderName }
                        appendMessage(
                            LanDirectMessage(
                                id = "lan_rx_${UUID.randomUUID()}",
                                peerKey = senderKey,
                                senderName = h.senderName.ifBlank { "局域网设备" },
                                isSelf = false,
                                isFile = false,
                                text = h.text,
                                timestamp = h.timestamp,
                            ),
                        )
                        onNotice("[局域网] ${h.senderName.ifBlank { "伙伴" }}：${h.text.take(24)}")
                    }

                    is LanDirectProtocol.ReceivedFrame.FileFrame -> {
                        val h = frame.header
                        val f = tmpFile
                        var displayPath: String? = null
                        if (f != null && f.exists()) {
                            val saved = MediaDownloadHelper.saveReceivedFile(
                                context = context,
                                sourceFile = f,
                                fileName = h.fileName.ifBlank { "lan_file.bin" },
                                mimeType = h.mimeType,
                                keepLocalPreviewCopy = false,
                            )
                            displayPath = saved.displayPath
                        }
                        val senderKey = h.senderId.ifBlank { h.senderName }
                        appendMessage(
                            LanDirectMessage(
                                id = "lan_rxf_${UUID.randomUUID()}",
                                peerKey = senderKey,
                                senderName = h.senderName.ifBlank { "局域网设备" },
                                isSelf = false,
                                isFile = true,
                                fileName = h.fileName,
                                fileSize = h.fileSize,
                                savedPath = displayPath,
                                verified = frame.verified,
                                timestamp = h.timestamp,
                            ),
                        )
                        onNotice("已通过局域网接收 ${h.fileName} (${displayPath ?: "下载目录"})")
                    }
                }
            }
        }
    }

    private fun registerMdnsService(
        mgr: NsdManager,
        displayName: String,
        clientId: String,
        port: Int,
    ) {
        val safeSuffix = clientId.takeLast(6).ifBlank { "node" }
        val info = NsdServiceInfo().apply {
            serviceName = "BayChat-$safeSuffix"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("cid", clientId)
            setAttribute("name", displayName.take(24))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                registeredServiceName = nsdServiceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(arg0: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startMdnsDiscovery(mgr: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_baychat")) return
                if (registeredServiceName.isNotBlank() && service.serviceName == registeredServiceName) return
                resolvePeerService(mgr, service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                peerMap.remove(service.serviceName)
                publishPeers()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun resolvePeerService(mgr: NsdManager, service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val hostAddr = resolved.host?.hostAddress ?: return
                val cid = resolved.attributes["cid"]?.toString(Charsets.UTF_8).orEmpty()
                if (cid.isNotBlank() && cid == selfClientId) return
                val display = resolved.attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                    ?: resolved.serviceName
                val peer = LanDirectPeer(
                    serviceName = resolved.serviceName,
                    displayName = display,
                    clientId = cid,
                    host = hostAddr,
                    port = resolved.port,
                    lastSeenAt = System.currentTimeMillis(),
                )
                peerMap[resolved.serviceName] = peer
                publishPeers()
            }
        }
        runCatching { mgr.resolveService(service, resolveListener) }
    }

    private fun publishPeers() {
        _peers.value = peerMap.values.sortedBy { it.displayName }
    }

    private fun appendMessage(msg: LanDirectMessage) {
        _messages.update { prev ->
            (prev + msg).takeLast(MAX_MESSAGES)
        }
    }

    private suspend fun computeUriSha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        } ?: return@withContext ""
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveLanIpv4(): String {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return ""
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return addr.hostAddress.orEmpty()
                }
            }
        }
        return ""
    }
}
