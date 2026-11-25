package com.baytale.baychat.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 统一底部抽屉弹窗：28dp 顶部圆角、36×5 fillStrong 把手与 40% 遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BayBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BayTheme.colors
    val sheetBg = if (colors.isDark) colors.surfaceElevated else colors.surface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = BayShapes.sheetTopShape,
        containerColor = sheetBg,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.40f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(BayShapes.fullShape)
                    .background(colors.fillStrong),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = BaySpacing.lg)
                .padding(bottom = BaySpacing.lg),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = BayType.headline,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = BaySpacing.xs, bottom = BaySpacing.md),
                )
            }
            content()
        }
    }
}

/**
 * 统一轻量模态对话框（基于 Dialog 自绘，禁止 AlertDialog）：宽 300dp、20dp 圆角、按钮横排。
 */
@Composable
fun BayDialog(
    title: String,
    message: String? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = BayTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(BayShapes.lgShape)
                .background(colors.surface)
                .padding(BaySpacing.xl),
        ) {
            Text(
                text = title,
                style = BayType.headline,
                color = colors.textPrimary,
            )

            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(BaySpacing.sm))
                Text(
                    text = message,
                    style = BayType.subhead,
                    color = colors.textSecondary,
                )
            }

            if (content != null) {
                Spacer(modifier = Modifier.height(BaySpacing.md))
                content()
            }

            Spacer(modifier = Modifier.height(BaySpacing.xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!dismissText.isNullOrBlank()) {
                    BayButton(
                        text = dismissText,
                        onClick = onDismiss,
                        style = BayButtonStyle.Neutral,
                        size = BayButtonSize.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                BayButton(
                    text = confirmText,
                    onClick = onConfirm,
                    style = if (destructive) BayButtonStyle.Destructive else BayButtonStyle.Primary,
                    size = BayButtonSize.Medium,
                    enabled = confirmEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * [BayActionSheet] 单条操作项描述。
 */
data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * iOS / 微信风格底部操作菜单：基于 [BayBottomSheet] + [BayGroup] 分组渲染，带底部独立取消按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BayActionSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    actions: List<SheetAction>,
    cancelLabel: String = "取消",
) {
    BayBottomSheet(
        onDismiss = onDismiss,
        title = title,
    ) {
        if (header != null) {
            header()
            Spacer(modifier = Modifier.height(BaySpacing.md))
        }

        if (actions.isNotEmpty()) {
            BayGroup {
                actions.forEachIndexed { index, action ->
                    BayListRow(
                        icon = action.icon,
                        title = action.label,
                        destructive = action.destructive,
                        showDivider = index < actions.lastIndex,
                        onClick = if (action.enabled) {
                            {
                                onDismiss()
                                action.onClick()
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(BaySpacing.md))
        }

        BayButton(
            text = cancelLabel,
            onClick = onDismiss,
            style = BayButtonStyle.Neutral,
            size = BayButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 悬浮胶囊 Toast 宿主：位于底部 Tab 栏上方（bottom = 72dp + navigationBarsPadding），不阻塞底层交互。
 */
@Composable
fun BayToastHost(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val pillBg = colors.toastBg

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 72.dp, start = BaySpacing.xxl, end = BaySpacing.xxl),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = !message.isNullOrBlank(),
            enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 3 },
        ) {
            Box(
                modifier = Modifier
                    .shadow(elevation = 6.dp, shape = BayShapes.fullShape, clip = false)
                    .clip(BayShapes.fullShape)
                    .background(pillBg)
                    .padding(horizontal = BaySpacing.lg, vertical = 10.dp),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
