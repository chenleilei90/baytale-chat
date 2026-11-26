package com.baytale.baychat.ui.screens.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 快速建房与直达台的高级配置草稿（B1：快速建房默认私密，不包含公开开关）。
 */
data class RoomOptionsDraft(
    val mode: RoomMode = RoomMode.EPHEMERAL,
    val retentionDays: Int = 1,
    val maxFileSizeMB: Int = 500,
    val password: String = "",
    val topic: String = "",
    val privatePairOnly: Boolean = false,
    val saveFixedFavorite: Boolean = true,
) {
    /** 生成供折叠行展示的精简摘要标签 */
    fun summaryLabel(): String {
        val modeLabel = if (mode == RoomMode.FIXED) "常驻" else "临时"
        val retentionLabel = if (retentionDays == 0) "即焚" else "${retentionDays}天"
        val sizeLabel = if (maxFileSizeMB <= 0) "不限" else FormatUtils.formatLimitMB(maxFileSizeMB)
        val pwdLabel = if (password.isNotBlank()) " · 加密" else ""
        return "$modeLabel · $retentionLabel · $sizeLabel$pwdLabel"
    }
}

private val RETENTION_CHOICES = listOf(
    0 to "阅后即焚",
    1 to "1 天",
    3 to "3 天",
    7 to "7 天",
)

private val FILE_LIMIT_CHOICES = listOf(
    0 to "不限",
    100 to "100MB",
    500 to "500MB",
)

/**
 * 房间高级选项底部抽屉：配置模式、保鲜天数、文件上限、可选密码、主题及私密/收藏开关。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomOptionsSheet(
    draft: RoomOptionsDraft,
    maxAttachmentMB: Int,
    maxRoomPeers: Int,
    onApply: (RoomOptionsDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var localDraft by remember(draft) { mutableStateOf(draft) }

    val availableFileLimits = remember(maxAttachmentMB) {
        if (maxAttachmentMB <= 0) {
            FILE_LIMIT_CHOICES
        } else {
            val filtered = FILE_LIMIT_CHOICES.filter { it.first in 1..maxAttachmentMB }
            if (filtered.any { it.first == maxAttachmentMB }) {
                filtered
            } else {
                filtered + (maxAttachmentMB to FormatUtils.formatLimitMB(maxAttachmentMB))
            }
        }
    }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "房间高级选项",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 1. 房间模式切换
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Text(
                    text = "房间模式",
                    style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                )
                BaySegmented(
                    options = listOf("临时 6 位", "常驻专属"),
                    selected = if (localDraft.mode == RoomMode.FIXED) 1 else 0,
                    onSelect = { idx ->
                        val nextMode = if (idx == 1) RoomMode.FIXED else RoomMode.EPHEMERAL
                        val nextDays = if (nextMode == RoomMode.FIXED && localDraft.retentionDays == 1) {
                            3
                        } else if (nextMode == RoomMode.EPHEMERAL && localDraft.retentionDays == 3) {
                            1
                        } else {
                            localDraft.retentionDays
                        }
                        localDraft = localDraft.copy(mode = nextMode, retentionDays = nextDays)
                    },
                )
            }

            // 2. 消息保鲜天数
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "消息保鲜天数",
                        style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Text(
                        text = if (localDraft.retentionDays == 0) "服务器零留存" else "活跃后保留 ${localDraft.retentionDays} 天",
                        style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                        color = if (localDraft.retentionDays == 0) colors.danger else colors.accent,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    RETENTION_CHOICES.forEach { (days, label) ->
                        BayChip(
                            label = label,
                            selected = localDraft.retentionDays == days,
                            onClick = { localDraft = localDraft.copy(retentionDays = days) },
                        )
                    }
                }
            }

            // 3. 单文件上限
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "单文件上限",
                        style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Text(
                        text = FormatUtils.formatLimitMB(localDraft.maxFileSizeMB),
                        style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.accent,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    availableFileLimits.forEach { (mb, label) ->
                        BayChip(
                            label = label,
                            selected = localDraft.maxFileSizeMB == mb,
                            onClick = { localDraft = localDraft.copy(maxFileSizeMB = mb) },
                        )
                    }
                }
            }

            // 4. 可选访问密码与房间主题
            var passwordError by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.md)) {
                BayTextField(
                    value = localDraft.password,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(20)
                        localDraft = localDraft.copy(password = digits)
                        if (digits.isEmpty() || digits.length >= 5) passwordError = false
                    },
                    placeholder = "访问密码（至少 5 位数字，留空免密）",
                    leadingIcon = Icons.Rounded.Lock,
                    isPassword = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (passwordError) {
                    Text(
                        text = "新建房间密码至少输入 5 位数字（留空则免密）",
                        style = BayType.caption,
                        color = colors.danger,
                    )
                }
                BayTextField(
                    value = localDraft.topic,
                    onValueChange = { localDraft = localDraft.copy(topic = it.take(40)) },
                    placeholder = "房间主题（可选，仅成员可见）",
                    leadingIcon = Icons.Rounded.Edit,
                )
            }

            // 5. 私密人数限制与收藏开关（B1：不提供公开开关，快速建房默认私密）
            BayGroup(
                footer = "新建房间默认保持私密，仅可通过口令或邀请链接进入。",
            ) {
                BayListRow(
                    icon = Icons.Rounded.Group,
                    title = "双人私密连接",
                    subtitle = if (localDraft.privatePairOnly) "仅限 2 人点对点连接" else "支持最多 ${maxRoomPeers.coerceAtLeast(2)} 人同时在线",
                    trailing = RowTrailing.Switch(
                        checked = localDraft.privatePairOnly,
                        onChange = { localDraft = localDraft.copy(privatePairOnly = it) },
                    ),
                    showDivider = true,
                )
                BayListRow(
                    icon = Icons.Rounded.Bookmark,
                    title = "收藏常驻房间",
                    subtitle = "创建或加入常驻房间时自动保存至常用列表",
                    trailing = RowTrailing.Switch(
                        checked = localDraft.saveFixedFavorite,
                        onChange = { localDraft = localDraft.copy(saveFixedFavorite = it) },
                    ),
                )
            }

            Spacer(modifier = Modifier.height(BaySpacing.xs))

            BayButton(
                text = "应用设置",
                onClick = {
                    val cleanPwd = localDraft.password.trim()
                    if (cleanPwd.isNotEmpty() && cleanPwd.length < 5) {
                        passwordError = true
                    } else {
                        onApply(localDraft.copy(password = cleanPwd))
                        onDismiss()
                    }
                },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
