package com.baytale.baychat.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// 后台通知快捷操作事件（BroadcastReceiver -> ViewModel 解耦总线）
sealed class IncomingActionEvent {
    data class AcceptFile(val peerId: String, val transferId: String) : IncomingActionEvent()
    data class RejectFile(val peerId: String, val transferId: String) : IncomingActionEvent()
}

object IncomingActionBus {
    private val _events = MutableSharedFlow<IncomingActionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<IncomingActionEvent> = _events.asSharedFlow()

    fun emit(event: IncomingActionEvent) {
        _events.tryEmit(event)
    }
}
