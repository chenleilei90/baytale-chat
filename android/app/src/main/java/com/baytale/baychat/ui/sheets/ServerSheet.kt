package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.launch

/**
 * 服务器地址与节点测速抽屉：支持修改自建节点地址、实时测速连通性及恢复默认节点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var draftUrl by remember(state.prefs.serverUrl) { mutableStateOf(state.prefs.serverUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testLatencyMs by remember { mutableStateOf<Long?>(null) }
    var testFailureMsg by remember { mutableStateOf<String?>(null) }

    fun normalizeAndValidate(raw: String): String? {
        val clean = raw.trim().trimEnd('/')
        return if (clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("http://", ignoreCase = true)
        ) {
            clean
        } else {
            null
        }
    }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "服务器地址与测速",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 当前节点摘要
            BayCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前信令与中继节点",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(BaySpacing.xs))
                Text(
                    text = state.prefs.serverUrl,
                    style = BayType.headline,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(BaySpacing.xs))
                val turnLabel = if (state.serverConfig.hasTurn) "已启用 TURN 中继兜底" else "仅点对点直连"
                Text(
                    text = "单房上限 ${state.serverConfig.maxRoomPeers} 人 · $turnLabel",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
            }

            // 地址输入
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Text(
                    text = "自定义服务器地址",
                    style = BayType.headline,
                    color = colors.textPrimary,
                )
                BayTextField(
                    value = draftUrl,
                    onValueChange = {
                        draftUrl = it
                        urlError = null
                        testLatencyMs = null
                        testFailureMsg = null
                    },
                    placeholder = BuildConfig.DEFAULT_SERVER_URL,
                    leadingIcon = Icons.Rounded.Dns,
                    error = urlError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                    ),
                )
            }

            // 测速结果反馈条
            if (testLatencyMs != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BayShapes.smShape)
                        .background(colors.success.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "节点连通正常 · 往返延迟 $testLatencyMs ms",
                        style = BayType.subhead,
                        color = colors.success,
                    )
                }
            } else if (!testFailureMsg.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BayShapes.smShape)
                        .background(colors.danger.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = testFailureMsg.orEmpty(),
                        style = BayType.subhead,
                        color = colors.danger,
                    )
                }
            }

            // 测速与恢复默认按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = if (testing) "测速中..." else "测速连通性",
                    onClick = {
                        val valid = normalizeAndValidate(draftUrl)
                        if (valid == null) {
                            urlError = "请输入以 https:// 或 http:// 开头的完整地址"
                            return@BayButton
                        }
                        testing = true
                        testLatencyMs = null
                        testFailureMsg = null
                        scope.launch {
                            vm.testServer(valid)
                                .onSuccess { ms ->
                                    testLatencyMs = ms
                                }
                                .onFailure { err ->
                                    testFailureMsg = err.message ?: "节点连接失败，请检查地址或网络"
                                }
                            testing = false
                        }
                    },
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Medium,
                    leadingIcon = Icons.Rounded.Speed,
                    loading = testing,
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                )

                BayButton(
                    text = "恢复默认节点",
                    onClick = {
                        draftUrl = BuildConfig.DEFAULT_SERVER_URL
                        urlError = null
                        testLatencyMs = null
                        testFailureMsg = null
                        vm.updatePrefs(state.prefs.copy(serverUrl = BuildConfig.DEFAULT_SERVER_URL))
                        vm.refreshServerConfig()
                        vm.showToast("已恢复默认服务器地址")
                    },
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Medium,
                    leadingIcon = Icons.Rounded.Restore,
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                )
            }

            // 保存并应用
            BayButton(
                text = "保存并应用",
                onClick = {
                    val valid = normalizeAndValidate(draftUrl)
                    if (valid == null) {
                        urlError = "请输入以 https:// 或 http:// 开头的完整地址"
                        return@BayButton
                    }
                    vm.updatePrefs(state.prefs.copy(serverUrl = valid))
                    vm.refreshServerConfig()
                    vm.showToast("已保存服务器节点地址")
                    onDismiss()
                },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
