package com.baytale.baychat.core.util

/**
 * 剥离字符串开头的前置空白、Emoji 表情符号、肤色/性别修饰符、VS15/VS16（U+FE0E/U+FE0F）、
 * 零宽连接符（ZWJ, U+200D）以及紧随其后的空格。
 *
 * 若字符串不以 Emoji 开头（例如普通中文、英文、数字、#标签等），则原样返回不做修改。
 */
fun stripLeadingEmoji(text: String): String {
    if (text.isEmpty()) return text

    var cursor = skipWhitespace(text, 0)
    if (cursor >= text.length) return text

    var strippedAny = false
    while (cursor < text.length) {
        val next = consumeEmojiCluster(text, cursor)
        if (next <= cursor) break
        strippedAny = true
        cursor = skipWhitespace(text, next)
    }

    return if (strippedAny) text.substring(cursor) else text
}

private fun skipWhitespace(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (!Character.isWhitespace(cp) && cp != 0x00A0 && cp != 0x200B) break
        i += Character.charCount(cp)
    }
    return i
}

/**
 * 尝试从 [start] 位置起消费一个完整 Emoji 簇（支持 ZWJ 连写、肤色修饰符、Tag 序列、Keycap 序列）。
 * 若当前位置不是 Emoji，返回 -1。
 */
private fun consumeEmojiCluster(text: String, start: Int): Int {
    var pos = consumeSingleEmojiAtom(text, start)
    if (pos < 0) return -1

    while (pos < text.length) {
        val cp = text.codePointAt(pos)
        when {
            // 变体选择符（VS15/VS16）、肤色修饰符、组合闭合框、Tag 序列
            isEmojiModifierOrJoinPiece(cp) -> {
                pos += Character.charCount(cp)
            }
            // 零宽连接符（ZWJ）：尝试将后续连接的 Emoji 原子一并吞并
            cp == 0x200D -> {
                val afterZwj = pos + 1
                if (afterZwj >= text.length) {
                    pos = afterZwj
                    break
                }
                val joinedEnd = consumeSingleEmojiAtom(text, afterZwj)
                if (joinedEnd > afterZwj) {
                    pos = joinedEnd
                } else {
                    // 消费孤立的 ZWJ 以避免残留不可见字符
                    pos = afterZwj
                    break
                }
            }
            else -> break
        }
    }
    return pos
}

/**
 * 消费单个基础 Emoji 原子（不含 ZWJ 后续连接部分），成功返回消费后的索引，失败返回 -1。
 */
private fun consumeSingleEmojiAtom(text: String, start: Int): Int {
    if (start >= text.length) return -1
    val cp = text.codePointAt(start)
    val charLen = Character.charCount(cp)

    // 1. Keycap 序列：0-9 / # / * 必须后跟可选 VS16 + U+20E3 才视为 Emoji，普通数字和 #/* 绝不误伤
    if (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) {
        var next = start + charLen
        if (next < text.length && text.codePointAt(next) == 0xFE0F) {
            next += 1
        }
        return if (next < text.length && text.codePointAt(next) == 0x20E3) {
            next + 1
        } else {
            -1
        }
    }

    // 2. 版权/注册/商标等常规文本符号仅在紧跟 VS16（U+FE0F）时按 Emoji 剥离
    if (cp == 0x00A9 || cp == 0x00AE || cp == 0x2122) {
        val next = start + charLen
        return if (next < text.length && text.codePointAt(next) == 0xFE0F) {
            next + 1
        } else {
            -1
        }
    }

    // 3. 区域指示符（国旗/地区旗双字符序列）
    if (cp in 0x1F1E6..0x1F1FF) {
        var next = start + charLen
        if (next < text.length) {
            val second = text.codePointAt(next)
            if (second in 0x1F1E6..0x1F1FF) {
                next += Character.charCount(second)
            }
        }
        return next
    }

    // 4. 标准非 ASCII Emoji 码点区间
    if (isBaseEmojiCodePoint(cp)) {
        return start + charLen
    }

    return -1
}

private fun isEmojiModifierOrJoinPiece(cp: Int): Boolean =
    cp == 0xFE0E ||
        cp == 0xFE0F ||
        cp == 0x20E3 ||
        cp in 0x1F3FB..0x1F3FF ||
        cp in 0xE0020..0xE007F

private fun isBaseEmojiCodePoint(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF ||
        cp in 0x2600..0x26FF ||
        cp in 0x2700..0x27BF ||
        cp in 0x231A..0x231B ||
        cp in 0x23E9..0x23FA ||
        cp in 0x2B05..0x2B07 ||
        cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
        cp == 0x203C || cp == 0x2049 ||
        cp == 0x2139 || cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA ||
        cp in 0x25AA..0x25AB || cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE ||
        cp == 0x2934 || cp == 0x2935 ||
        cp == 0x3030 || cp == 0x303D ||
        cp == 0x3297 || cp == 0x3299
