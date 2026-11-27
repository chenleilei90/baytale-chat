package com.baytale.baychat.ui.screens.lobby

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.NearbyInvite
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.AppShortcutAction
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.sheets.PickupHubSheet
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val BACK_EXIT_WINDOW_MS = 2000L

/**
 * 大厅 4-Tab 主宿主（快传 / 发现 / 传输 / 我的）：支持底部 4 菜单手势左右滑动丝滑切换、全局直连邀请横幅与确认弹窗。
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
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val selectedTab = pagerState.currentPage
    val scrollProgress = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 3f)
    var showPickupHub by rememberSaveable { mutableStateOf(false) }
    var lastBackPressAt by remember { mutableLongStateOf(0L) }

    // 统一在页面滑落到新 Tab 时触发轻触感反馈（点击切换由 BayTabBar 先行跳过重复触发，避免双击感）
    var skipNextSettledHaptic by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                if (skipNextSettledHaptic) {
                    skipNextSettledHaptic = false
                } else {
                    BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                }
            }
    }

    val nav = remember(vm, onRequestQrScan, scope, pagerState) {
        object : LobbyNav {
            override fun switchTab(index: Int) {
                scope.launch {
                    pagerState.animateScrollToPage(index.coerceIn(0, 3))
                }
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
                pagerState.scrollToPage(0)
                vm.consumePendingAppAction()
            }
            AppShortcutAction.PICKUP -> {
                pagerState.scrollToPage(0)
                showPickupHub = true
                vm.consumePendingAppAction()
            }
            else -> Unit
        }
    }

    // 若收到外部待发分享或待加入口令，切回「快传」主入口便于直达
    LaunchedEffect(state.pendingJoinCode) {
        if (!state.pendingJoinCode.isNullOrBlank()) {
            pagerState.animateScrollToPage(0)
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
            scope.launch { pagerState.animateScrollToPage(0) }
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
        // UX-15：全局顶部直连邀请横幅（在任意大厅 Tab 均可实时收到并接拒）
        AnimatedVisibility(
            visible = state.incomingNearbyInvites.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.xs),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                state.incomingNearbyInvites.forEach { invite ->
                    LobbyNearbyInviteBanner(
                        invite = invite,
                        onAccept = { vm.respondNearbyInvite(invite, accept = true) },
                        onDecline = { vm.respondNearbyInvite(invite, accept = false) },
                    )
                }
            }
        }

        // 支持左右手势丝滑滑动切换底部 4 个页面
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
            scrollProgress = scrollProgress,
            activeTransferCount = activeTransferCount,
            mineHasBadge = mineHasBadge,
            hapticEnabled = state.prefs.hapticEnabled,
            onSelectTab = { target ->
                skipNextSettledHaptic = true
                scope.launch {
                    pagerState.animateScrollToPage(target.coerceIn(0, 3))
                }
            },
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
}

@Composable
private fun LobbyNearbyInviteBanner(
    invite: NearbyInvite,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = BayTheme.colors
    BayCard(
        modifier = Modifier.fillMaxWidth(),
        padding = BaySpacing.md,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                modifier = Modifier.weight(1f),
            ) {
                BayAvatar(emojiId = invite.fromAvatar, size = 36.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${invite.fromName} 邀请直连",
                        style = BayType.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "房间 #${invite.roomCode}",
                        style = BayType.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                BayButton(
                    text = "婉拒",
                    onClick = onDecline,
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Small,
                )
                BayButton(
                    text = "接受",
                    onClick = onAccept,
                    style = BayButtonStyle.Primary,
                    size = BayButtonSize.Small,
                )
            }
        }
    }
}
