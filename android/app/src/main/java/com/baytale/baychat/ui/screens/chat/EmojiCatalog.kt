package com.baytale.baychat.ui.screens.chat

/**
 * 常用 Unicode 表情码点目录（纯码点数组存储，避免源码中出现字面量 Emoji，兼顾双端一致渲染）。
 */
internal val EMOJI_CODEPOINTS: List<IntArray> = listOf(
    intArrayOf(0x1F600), intArrayOf(0x1F603), intArrayOf(0x1F604), intArrayOf(0x1F601),
    intArrayOf(0x1F606), intArrayOf(0x1F605), intArrayOf(0x1F923), intArrayOf(0x1F602),
    intArrayOf(0x1F642), intArrayOf(0x1F643), intArrayOf(0x1F609), intArrayOf(0x1F60A),
    intArrayOf(0x1F607), intArrayOf(0x1F970), intArrayOf(0x1F60D), intArrayOf(0x1F929),
    intArrayOf(0x1F618), intArrayOf(0x1F617), intArrayOf(0x1F61A), intArrayOf(0x1F619),
    intArrayOf(0x1F60B), intArrayOf(0x1F61B), intArrayOf(0x1F61C), intArrayOf(0x1F92A),
    intArrayOf(0x1F61D), intArrayOf(0x1F911), intArrayOf(0x1F917), intArrayOf(0x1F92D),
    intArrayOf(0x1F92B), intArrayOf(0x1F914), intArrayOf(0x1F910), intArrayOf(0x1F928),
    intArrayOf(0x1F610), intArrayOf(0x1F611), intArrayOf(0x1F636), intArrayOf(0x1F60F),
    intArrayOf(0x1F612), intArrayOf(0x1F644), intArrayOf(0x1F62C), intArrayOf(0x1F925),
    intArrayOf(0x1F60C), intArrayOf(0x1F614), intArrayOf(0x1F62A), intArrayOf(0x1F924),
    intArrayOf(0x1F634), intArrayOf(0x1F637), intArrayOf(0x1F912), intArrayOf(0x1F915),
    intArrayOf(0x1F922), intArrayOf(0x1F92E), intArrayOf(0x1F927), intArrayOf(0x1F975),
    intArrayOf(0x1F976), intArrayOf(0x1F974), intArrayOf(0x1F635), intArrayOf(0x1F92F),
    intArrayOf(0x1F920), intArrayOf(0x1F973), intArrayOf(0x1F60E), intArrayOf(0x1F913),
    intArrayOf(0x1F9D0), intArrayOf(0x1F615), intArrayOf(0x1F61F), intArrayOf(0x1F641),
    intArrayOf(0x1F62E), intArrayOf(0x1F62F), intArrayOf(0x1F632), intArrayOf(0x1F633),
    intArrayOf(0x1F97A), intArrayOf(0x1F626), intArrayOf(0x1F627), intArrayOf(0x1F628),
    intArrayOf(0x1F630), intArrayOf(0x1F625), intArrayOf(0x1F622), intArrayOf(0x1F62D),
    intArrayOf(0x1F631), intArrayOf(0x1F616), intArrayOf(0x1F623), intArrayOf(0x1F61E),
    intArrayOf(0x1F613), intArrayOf(0x1F629), intArrayOf(0x1F62B), intArrayOf(0x1F971),
    intArrayOf(0x1F624), intArrayOf(0x1F621), intArrayOf(0x1F620), intArrayOf(0x1F92C),
    intArrayOf(0x1F44D), intArrayOf(0x1F44E), intArrayOf(0x1F44C), intArrayOf(0x1F44F),
    intArrayOf(0x1F64C), intArrayOf(0x1F91D), intArrayOf(0x1F64F), intArrayOf(0x1F4AA),
    intArrayOf(0x1F440), intArrayOf(0x1F44B), intArrayOf(0x1F91A), intArrayOf(0x1F596),
    intArrayOf(0x1F91E), intArrayOf(0x1F91F), intArrayOf(0x1F918), intArrayOf(0x1F49B),
    intArrayOf(0x1F499), intArrayOf(0x1F49C), intArrayOf(0x1F49A), intArrayOf(0x1F9E1),
    intArrayOf(0x1F494), intArrayOf(0x1F4AF), intArrayOf(0x1F525), intArrayOf(0x1F389),
    intArrayOf(0x1F38A), intArrayOf(0x1F381), intArrayOf(0x1F31F), intArrayOf(0x1F4A1),
    intArrayOf(0x1F4AC), intArrayOf(0x1F4AD), intArrayOf(0x1F388), intArrayOf(0x1F680),
)

internal fun emojiString(cps: IntArray): String = String(cps, 0, cps.size)
