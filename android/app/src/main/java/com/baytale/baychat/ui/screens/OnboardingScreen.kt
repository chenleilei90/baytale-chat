package com.baytale.baychat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val highlights: List<String>,
)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = Icons.Rounded.Bolt,
        title = "点对点局域网与跨网快传",
        subtitle = "免注册账号，输入口令或扫码即可与身边或远端设备建立直接连接。",
        highlights = listOf(
            "同局域网自动发现，支持跨网点对点直连传输",
            "原图、大文件、文件夹打包及语音消息直接送达",
            "支持跨设备剪贴板同步，由接收方主动决定是否复制",
        ),
    ),
    OnboardingPage(
        icon = Icons.Rounded.Inventory2,
        title = "6 位离线取件码",
        subtitle = "对方暂时不在线时，也可生成 6 位取件码完成跨设备异步传递。",
        highlights = listOf(
            "支持设置取件密码与 1 至 72 小时自动过期",
            "提供阅后即焚模式，下载完成后自动销毁",
            "支持断点续传下载与本地取件记录管理",
        ),
    ),
    OnboardingPage(
        icon = Icons.Rounded.VerifiedUser,
        title = "默认私密与可控数据",
        subtitle = "你的传输默认保持私密，消息留存时长与本地空间完全由你掌控。",
        highlights = listOf(
            "快速建房默认不公开，仅可通过口令或链接加入",
            "支持 0 天零缓存或 1 至 7 天自动清理云端缓存",
            "支持按分类查看并清理本地传输记录与缓存空间",
        ),
    ),
)

/**
 * 首次使用 3 步极简引导页：支持滑动切换、顶部跳过与末页开始使用。
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGES.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.lg),
    ) {
        // 顶部跳过按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            BayButton(
                text = "跳过",
                onClick = onComplete,
                style = BayButtonStyle.Plain,
                size = BayButtonSize.Small,
            )
        }

        // 3 页内容区
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { index ->
            OnboardingPageContent(page = ONBOARDING_PAGES[index])
        }

        // 页面指示点
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = BaySpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(ONBOARDING_PAGES.size) { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(if (active) 20.dp else 6.dp)
                        .clip(BayShapes.fullShape)
                        .background(if (active) colors.accent else colors.fillStrong),
                )
            }
        }

        // 底部主操作按钮
        BayButton(
            text = if (isLastPage) "开始使用" else "下一步",
            onClick = {
                if (isLastPage) {
                    onComplete()
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
            style = BayButtonStyle.Primary,
            size = BayButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val colors = BayTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = BaySpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(38.dp),
            )
        }

        Spacer(modifier = Modifier.height(BaySpacing.xxl))

        Text(
            text = page.title,
            style = BayType.title1,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(BaySpacing.md))

        Text(
            text = page.subtitle,
            style = BayType.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(BaySpacing.xxl))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BayShapes.lgShape)
                .background(colors.surface)
                .padding(BaySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            page.highlights.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                    Text(
                        text = item,
                        style = BayType.subhead,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}
