package com.baytale.baychat.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baytale.baychat.core.util.BayFileKind
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.classifyFile
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 线性进度条：平滑动画过渡，圆角胶囊轨道。
 */
@Composable
fun BayProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    trackColor: Color? = null,
) {
    val colors = BayTheme.colors
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "bayProgressFraction",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(animatedFraction, 0f..1f)
            }
            .clip(BayShapes.fullShape)
            .background(trackColor ?: colors.fill),
    ) {
        if (animatedFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .clip(BayShapes.fullShape)
                    .background(color ?: colors.accent),
            )
        }
    }
}

/**
 * 环形进度指示器：自定义 Canvas 绘制圆角弧线，零默认紫色。
 */
@Composable
fun BayRingProgress(
    fraction: Float,
    size: Dp = 36.dp,
    strokeWidth: Dp = 3.dp,
    trackColor: Color? = null,
    progressColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "bayRingProgress",
    )
    val resolvedTrack = trackColor ?: colors.fill
    val resolvedProgress = progressColor ?: colors.accent

    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = resolvedTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )

        if (animatedFraction > 0f) {
            drawArc(
                color = resolvedProgress,
                startAngle = -90f,
                sweepAngle = 360f * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 预设头像圆形徽章：通过 [FormatUtils.presetEmoji] 解析表情头像，配合柔和主题底色。
 */
@Composable
fun BayAvatar(
    emojiId: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: Shape = CircleShape,
) {
    val colors = BayTheme.colors
    val glyph = FormatUtils.presetEmoji(emojiId)
    val fontSp = (size.value * 0.48f).toInt().coerceIn(12, 36).sp

    Box(
        modifier = modifier
            .clearAndSetSemantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .size(size)
            .clip(shape)
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontSize = fontSp,
            lineHeight = fontSp,
        )
    }
}

/**
 * 角标红点或未读数字胶囊（设计系统中唯一允许使用背景色 2dp 镂空描边的组件）。
 */
@Composable
fun BayBadge(
    count: Int? = null,
    dotOnly: Boolean = false,
    color: Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val badgeColor = color ?: colors.danger

    if (dotOnly || count == null) {
        Box(
            modifier = modifier
                .size(10.dp)
                .border(width = 2.dp, color = colors.bg, shape = CircleShape)
                .clip(CircleShape)
                .background(badgeColor),
        )
        return
    }

    if (count <= 0) return

    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .border(width = 2.dp, color = colors.bg, shape = BayShapes.fullShape)
            .clip(BayShapes.fullShape)
            .background(badgeColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * 苹果风格页面顶部大标题栏：左侧 28sp 大标题与可选副标题，右侧操作图标区。
 */
@Composable
fun BayLargeTitleHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = BayType.largeTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = BayType.subhead,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * 语义化文件类型图标方块：自动根据扩展名与 MIME 类型匹配 8 种 [BayFileTypeColors] 与矢量图标。
 */
@Composable
fun BayFileTypeIcon(
    fileName: String,
    mimeType: String? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val kind = classifyFile(fileName = fileName, mimeType = mimeType)

    val (tint, vector) = when (kind) {
        BayFileKind.PDF -> colors.fileTypeColors.pdf to Icons.Rounded.PictureAsPdf
        BayFileKind.DOCUMENT -> colors.fileTypeColors.doc to Icons.Rounded.Description
        BayFileKind.ARCHIVE -> colors.fileTypeColors.archive to Icons.Rounded.FolderZip
        BayFileKind.APK -> colors.fileTypeColors.apk to Icons.Rounded.Android
        BayFileKind.IMAGE -> colors.fileTypeColors.image to Icons.Rounded.Image
        BayFileKind.VIDEO -> colors.fileTypeColors.video to Icons.Rounded.Videocam
        BayFileKind.AUDIO -> colors.fileTypeColors.audio to Icons.Rounded.AudioFile
        BayFileKind.OTHER -> colors.fileTypeColors.other to Icons.Rounded.InsertDriveFile
    }

    val cornerRadius = (size * 0.25f).coerceIn(8.dp, 14.dp)
    val bgAlpha = if (colors.isDark) 0.22f else 0.12f

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tint.copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}
