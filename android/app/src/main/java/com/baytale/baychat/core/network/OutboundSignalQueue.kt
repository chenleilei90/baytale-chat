package com.baytale.baychat.core.network

import com.baytale.baychat.core.model.SignalEnvelope

// B17：纯 Kotlin 断线信令发送队列（上限 200 条、10 分钟 TTL 过期淘汰，支持 JVM 单元测试）
class OutboundSignalQueue(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class QueuedItem(
        val envelope: SignalEnvelope,
        val enqueuedAt: Long,
    )

    data class DrainResult(
        val readyToSend: List<SignalEnvelope>,
        val expiredMessageIds: List<String>,
    )

    private val queue = ArrayDeque<QueuedItem>()

    @Synchronized
    fun enqueue(env: SignalEnvelope): String? {
        if (env.type == "join" || env.type == "ping") return null
        var evictedMsgId: String? = null
        if (queue.size >= maxSize) {
            val evicted = queue.removeFirst()
            evictedMsgId = evicted.envelope.message?.id
        }
        queue.addLast(QueuedItem(env, clock()))
        return evictedMsgId
    }

    @Synchronized
    fun drainValid(): DrainResult {
        if (queue.isEmpty()) return DrainResult(emptyList(), emptyList())
        val now = clock()
        val ready = mutableListOf<SignalEnvelope>()
        val expiredIds = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (now - item.enqueuedAt <= ttlMs) {
                ready.add(item.envelope)
            } else {
                item.envelope.message?.id?.let { expiredIds.add(it) }
            }
        }
        return DrainResult(readyToSend = ready, expiredMessageIds = expiredIds)
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 200
        const val DEFAULT_TTL_MS = 10L * 60L * 1000L
    }
}
