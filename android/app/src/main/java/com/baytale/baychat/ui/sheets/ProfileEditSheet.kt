package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BaySectionHeader
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 个人身份资料编辑弹窗：支持修改显示昵称、随机生成与选择预设头像。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditSheet(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var draftName by remember(state.profile.name) { mutableStateOf(state.profile.name) }
    var draftAvatar by remember(state.profile.avatar) { mutableStateOf(state.profile.avatar) }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "编辑个人资料",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 顶部实时预览区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.mdShape)
                    .background(colors.fill)
                    .padding(BaySpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
            ) {
                BayAvatar(
                    emojiId = draftAvatar,
                    size = 56.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = draftName.trim().ifBlank { "匿名伙伴" },
                        style = BayType.headline,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "本机身份标识 ${state.profile.clientId.take(12)}",
                        style = BayType.footnote,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BayButton(
                    text = "随机换一个",
                    onClick = {
                        draftName = FormatUtils.generateRandomName()
                        draftAvatar = FormatUtils.PRESET_AVATARS.random().first
                    },
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Small,
                    leadingIcon = Icons.Rounded.Casino,
                )
            }

            // 昵称输入区
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                BaySectionHeader(
                    title = "显示昵称",
                    actionLabel = "${draftName.trim().length}/24",
                    onAction = {},
                )
                BayTextField(
                    value = draftName,
                    onValueChange = { next ->
                        if (next.length <= 24) {
                            draftName = next
                        }
                    },
                    placeholder = "输入显示昵称（留空则自动生成）",
                    leadingIcon = Icons.Rounded.Person,
                    singleLine = true,
                )
            }

            // 预设头像选择网格
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Text(
                    text = "选择预设头像",
                    style = BayType.headline,
                    color = colors.textPrimary,
                )
                FormatUtils.PRESET_AVATARS.chunked(4).forEach { rowAvatars ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        rowAvatars.forEach { (id, _) ->
                            val selected = draftAvatar == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .bayClickable(haptic = state.prefs.hapticEnabled) {
                                        draftAvatar = id
                                    }
                                    .clip(BayShapes.mdShape)
                                    .background(if (selected) colors.accentSoft else colors.fill)
                                    .padding(vertical = BaySpacing.md),
                                contentAlignment = Alignment.Center,
                            ) {
                                BayAvatar(
                                    emojiId = id,
                                    size = 44.dp,
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 6.dp, end = 6.dp)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(colors.accent),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "已选中",
                                            tint = colors.onAccent,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(BaySpacing.xs))

            // 保存按钮
            BayButton(
                text = "保存资料",
                onClick = {
                    vm.updateProfile(draftName, draftAvatar)
                    onDismiss()
                },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
