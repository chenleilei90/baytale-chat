package com.baytale.baychat.ui.screens.lobby

// 大厅 4-Tab（0 快传 / 1 发现 / 2 传输 / 3 我的）统一导航回调接口
interface LobbyNav {
    fun switchTab(index: Int)
    fun requestQrScan()
    fun restoreRoom()
}
