package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme

/**
 * 屏蔽名单管理抽屉：展示已持久化屏蔽的伙伴，支持一键解除屏蔽。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedListSheet(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val blockedList = state.blockedClients

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "屏蔽名单管理",
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (blockedList.isEmpty()) {
                BayEmptyState(
                    icon = Icons.Rounded.VerifiedUser,
                    title = "暂无屏蔽对象",
                    message = "在房间成员列表屏蔽某个伙伴后，将在此处统一管理并可随时解除。",
                    modifier = Modifier.padding(vertical = BaySpacing.md),
                )
            } else {
                BayGroup(
                    header = "共 ${blockedList.size} 位已屏蔽伙伴",
                    footer = "解除屏蔽后，对方可再次与你建立直连或收发文件。",
                ) {
                    blockedList.forEachIndexed { index, peer ->
                        val shortId = peer.clientId.removePrefix("name:").take(12)
                        BayListRow(
                            icon = Icons.Rounded.Block,
                            iconTint = colors.danger,
                            title = peer.name.ifBlank { "未命名伙伴" },
                            subtitle = "标识 $shortId · ${FormatUtils.formatRelativeTime(peer.blockedAt)}",
                            trailing = RowTrailing.Custom {
                                BayButton(
                                    text = "解除",
                                    onClick = { vm.unblockClient(peer.clientId) },
                                    style = BayButtonStyle.Secondary,
                                    size = BayButtonSize.Small,
                                )
                            },
                            showDivider = index < blockedList.lastIndex,
                        )
                    }
                }
            }
        }
    }
}
