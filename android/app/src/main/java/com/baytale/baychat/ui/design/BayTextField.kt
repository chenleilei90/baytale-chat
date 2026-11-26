package com.baytale.baychat.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * Bay Design 2.0 填充式输入框（基于 [BasicTextField]，零描边、聚焦加深填充、支持密码与错误提示）。
 */
@Composable
fun BayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    error: String? = null,
    large: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color? = null,
    focusedContainerColor: Color? = null,
    minHeightDp: Dp = if (large) 52.dp else 48.dp,
    verticalPadding: Dp = if (singleLine) 8.dp else 10.dp,
) {
    val colors = BayTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val baseBg = containerColor ?: colors.fill
    val focusBg = focusedContainerColor ?: colors.fillStrong
    val containerBg by animateColorAsState(
        targetValue = if (focused) focusBg else baseBg,
        animationSpec = tween(durationMillis = 150),
        label = "bayTextFieldBg",
    )

    val resolvedMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(1)
    val resolvedMinLines = if (singleLine) 1 else minLines.coerceIn(1, resolvedMaxLines)
    val textStyle = (if (large) BayType.headline else BayType.body).copy(
        color = colors.textPrimary,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = resolvedMinLines,
            maxLines = resolvedMaxLines,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            cursorBrush = SolidColor(colors.accent),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeightDp)
                        .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
                        .clip(BayShapes.smShape)
                        .background(containerBg)
                        .padding(
                            horizontal = BaySpacing.md,
                            vertical = verticalPadding,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = if (focused) colors.accent else colors.textSecondary,
                            modifier = Modifier
                                .padding(top = if (singleLine) 0.dp else 2.dp)
                                .size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(BaySpacing.sm))
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }

                    if (trailing != null) {
                        Spacer(modifier = Modifier.width(BaySpacing.sm))
                        trailing()
                    }
                }
            },
        )

        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = error,
                style = BayType.footnote,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = BaySpacing.xs),
            )
        }
    }
}
