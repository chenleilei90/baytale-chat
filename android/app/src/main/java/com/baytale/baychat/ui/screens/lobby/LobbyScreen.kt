package com.baytale.baychat.ui.screens.lobby

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.AppShortcutAction
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.sheets.PickupHubSheet
import com.baytale.baychat.ui.theme.BayTheme

private const val BACK_EXIT_WINDOW_MS = 2000L

/**
 * 大厅 4-Tab 主宿主（快传 / 发现 / 传输 / 我的）：统一管理 Tab 切换、快捷方式路由、返回策略与全局确认弹窗。
 */
@Composable
fun LobbyScreen(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onRequestQrScan: () -> Unit,
    onMoveTaskToBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPickupHub by rememberSaveable { mutableStateOf(false) }
    var lastBackPressAt by remember { mutableLongStateOf(0L) }

    val nav = remember(vm, onRequestQrScan) {
        object : LobbyNav {
            override fun switchTab(index: Int) {
                selectedTab = index.coerceIn(0, 3)
            }

            override fun requestQrScan() {
                onRequestQrScan()
            }

            override fun restoreRoom() {
                vm.restoreRoom()
            }
        }
    }

    // 响应桌面长按快捷方式路由（快传 / 取件码）
    LaunchedEffect(state.pendingAppAction, state.privacyAccepted) {
        if (!state.privacyAccepted) return@LaunchedEffect
        when (state.pendingAppAction) {
            AppShortcutAction.QUICK_SEND -> {
                selectedTab = 0
                vm.consumePendingAppAction()
            }
            AppShortcutAction.PICKUP -> {
                selectedTab = 0
                showPickupHub = true
                vm.consumePendingAppAction()
            }
            else -> Unit
        }
    }

    // 若收到外部待发分享或待加入口令，切回「快传」主入口便于直达
    LaunchedEffect(state.pendingJoinCode) {
        if (!state.pendingJoinCode.isNullOrBlank()) {
            selectedTab = 0
        }
    }

    // 切换 Tab 时按需静默刷新公开房与传输记录
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> vm.refreshPublicRoomsIfStale()
            2 -> vm.refreshTransferCenter()
        }
    }

    // B12：非「快传」Tab 按返回切回「快传」，「快传」Tab 双击返回退至桌面
    BackHandler(enabled = state.privacyAccepted && state.prefs.onboardingDone) {
        if (selectedTab != 0) {
            selectedTab = 0
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressAt <= BACK_EXIT_WINDOW_MS) {
                onMoveTaskToBack()
            } else {
                lastBackPressAt = now
                vm.showToast("再按一次返回桌面")
            }
        }
    }

    // 统计进行中的传输任务总数，驱动「传输」Tab 角标
    val roomTransferCount = state.activeRoom?.messages?.count {
        it.transfer?.status == TransferStatus.TRANSFERRING
    } ?: 0
    val pickupActiveCount = if (state.pickupProgress?.active == true) 1 else 0
    val folderPackCount = if (state.packingFolder != null) 1 else 0
    val activeTransferCount = roomTransferCount + pickupActiveCount + folderPackCount

    // OTA 有可用更新或已就绪时在「我的」Tab 显示轻量提示点
    val ota = state.otaState
    val isIgnoredOta = ota.release != null && state.prefs.ignoredVersionCode == ota.release.versionCode
    val mineHasBadge = ota.release != null && (
        ota.status == OtaUpdateStatus.READY_TO_INSTALL ||
            ota.status == OtaUpdateStatus.DOWNLOADING ||
            (ota.status == OtaUpdateStatus.AVAILABLE && !isIgnoredOta)
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        Crossfade(
            targetState = selectedTab,
            animationSpec = tween(durationMillis = 150),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "bayLobbyTabCrossfade",
        ) { tab ->
            when (tab) {
                0 -> QuickTab(
                    state = state,
                    vm = vm,
                    nav = nav,
                    onOpenPickupHub = { showPickupHub = true },
                )
                1 -> DiscoverTab(
                    state = state,
                    vm = vm,
                    nav = nav,
                )
                2 -> TransferTab(
                    state = state,
                    vm = vm,
                    nav = nav,
                )
                else -> MineTab(
                    state = state,
                    vm = vm,
                    nav = nav,
                )
            }
        }

        BayTabBar(
            selectedTab = selectedTab,
            activeTransferCount = activeTransferCount,
            mineHasBadge = mineHasBadge,
            hapticEnabled = state.prefs.hapticEnabled,
            onSelectTab = { target -> selectedTab = target },
        )
    }

    // 离线取件码中心弹窗
    if (showPickupHub) {
        PickupHubSheet(
            state = state,
            vm = vm,
            onDismiss = { showPickupHub = false },
        )
    }

    // B6：输入未知口令准备创建常驻房前的二次确认弹窗
    val pendingCreate = state.pendingCreateConfirm
    if (pendingCreate != null) {
        BayDialog(
            title = "创建常驻专属房 #${pendingCreate.code}？",
            message = "当前未找到口令为「${pendingCreate.code}」的房间。是否以此口令新建一个私密常驻房间并进入？",
            confirmText = "创建并进入",
            onConfirm = { vm.confirmCreateFixedRoom() },
            dismissText = "取消",
            onDismiss = { vm.dismissCreateFixedConfirm() },
        )
    }

    // B13：已有活跃/挂起房间时尝试进入新房间的确认弹窗
    val pendingSwitch = state.pendingSwitchRoom
    if (pendingSwitch != null) {
        val currentCode = state.activeRoom?.roomCode.orEmpty()
        BayDialog(
            title = "切换到房间 #${pendingSwitch.targetCode}？",
            message = "当前仍连接在房间 #$currentCode。确认切换后将断开原房间连接并进入新房间。",
            confirmText = "切换房间",
            onConfirm = { vm.confirmSwitchRoom() },
            dismissText = "留在原房",
            onDismiss = { vm.dismissSwitchRoom() },
        )
    }

    // B12/B23：进程冷启动 10 分钟内恢复上次活跃房间确认弹窗
    val recoverable = state.lastRecoverableRoom
    if (recoverable != null && state.activeRoom == null && state.privacyAccepted) {
        BayDialog(
            title = "恢复上次会话 #${recoverable.code}？",
            message = "检测到你不久前退出了房间 #${recoverable.code}，是否立即重新加入？",
            confirmText = "重新加入",
            onConfirm = { vm.recoverLastRoom() },
            dismissText = "暂不恢复",
            onDismiss = { vm.dismissRecoverRoom() },
        )
    }
}
