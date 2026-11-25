package com.baytale.baychat.ui.screens.lobby

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.design.BayBadge
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

private data class BayTabSpec(
    val index: Int,
    val label: String,
    val icon: ImageVector,
)

private val BAY_TABS = listOf(
    BayTabSpec(0, "快传", Icons.Rounded.Bolt),
    BayTabSpec(1, "发现", Icons.Rounded.Explore),
    BayTabSpec(2, "传输", Icons.Rounded.SwapVert),
    BayTabSpec(3, "我的", Icons.Rounded.Person),
)

/**
 * 大厅 48dp 紧凑底部导航栏：纯色 surface 承托 + 顶部 0.5dp 发丝线，无描边无水波纹。
 */
@Composable
fun BayTabBar(
    selectedTab: Int,
    activeTransferCount: Int = 0,
    mineHasBadge: Boolean = false,
    hapticEnabled: Boolean = true,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        // 顶部 0.5dp 发丝分割线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.separator),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(48.dp)
                .padding(horizontal = BaySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BAY_TABS.forEach { tab ->
                val selected = selectedTab == tab.index
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "tabScale_${tab.index}",
                )
                val tint = if (selected) colors.accent else colors.textSecondary
                val interactionSource = remember(tab.index) { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!selected) {
                                    BayHaptic.tap(haptic, enabled = hapticEnabled)
                                    onSelectTab(tab.index)
                                }
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(width = 36.dp, height = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier
                                .size(21.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                        if (tab.index == 2 && activeTransferCount > 0) {
                            BayBadge(
                                count = activeTransferCount,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-3).dp),
                            )
                        } else if (tab.index == 3 && mineHasBadge) {
                            BayBadge(
                                dotOnly = true,
                                color = colors.accent,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 1.dp, y = 0.dp),
                            )
                        }
                    }

                    Text(
                        text = tab.label,
                        style = BayType.caption.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
