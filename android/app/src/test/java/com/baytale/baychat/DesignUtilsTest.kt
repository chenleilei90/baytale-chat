package com.baytale.baychat

import com.baytale.baychat.core.util.BayFileKind
import com.baytale.baychat.core.util.classifyFile
import com.baytale.baychat.core.util.stripLeadingEmoji
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bay Design 2.0 纯工具函数单元测试：验证文件类型识别与开头 Emoji 剥离边界行为。
 */
class DesignUtilsTest {

    @Test
    fun testClassifyFileByExtensionAndMime() {
        // 图片
        assertEquals(BayFileKind.IMAGE, classifyFile("photo.jpg"))
        assertEquals(BayFileKind.IMAGE, classifyFile("IMG_001.HEIC"))
        assertEquals(BayFileKind.IMAGE, classifyFile("avatar.webp", "application/octet-stream"))
        assertEquals(BayFileKind.IMAGE, classifyFile("blob_without_ext", "image/png"))

        // 视频
        assertEquals(BayFileKind.VIDEO, classifyFile("clip.mp4"))
        assertEquals(BayFileKind.VIDEO, classifyFile("movie.MKV"))
        assertEquals(BayFileKind.VIDEO, classifyFile("stream", "video/webm"))

        // 音频
        assertEquals(BayFileKind.AUDIO, classifyFile("voice.m4a"))
        assertEquals(BayFileKind.AUDIO, classifyFile("lossless.FLAC"))
        assertEquals(BayFileKind.AUDIO, classifyFile("record", "audio/ogg; codecs=opus"))

        // 压缩包
        assertEquals(BayFileKind.ARCHIVE, classifyFile("backup.tar.gz"))
        assertEquals(BayFileKind.ARCHIVE, classifyFile("bundle.7z"))
        assertEquals(BayFileKind.ARCHIVE, classifyFile("raw", "application/zip"))

        // PDF
        assertEquals(BayFileKind.PDF, classifyFile("report.pdf"))
        assertEquals(BayFileKind.PDF, classifyFile("CONTRACT.PDF"))
        assertEquals(BayFileKind.PDF, classifyFile("download", "application/pdf"))

        // 文档
        assertEquals(BayFileKind.DOCUMENT, classifyFile("spec.docx"))
        assertEquals(BayFileKind.DOCUMENT, classifyFile("data.csv"))
        assertEquals(BayFileKind.DOCUMENT, classifyFile("README.md"))
        assertEquals(BayFileKind.DOCUMENT, classifyFile("notes", "text/plain; charset=utf-8"))

        // APK（优先于通用 zip MIME）
        assertEquals(BayFileKind.APK, classifyFile("BayChat-v2.0.apk"))
        assertEquals(BayFileKind.APK, classifyFile("BayChat-v2.0.apk", "application/zip"))
        assertEquals(
            BayFileKind.APK,
            classifyFile("package", "application/vnd.android.package-archive"),
        )

        // 路径、URL 参数与兜底未知类型
        assertEquals(
            BayFileKind.PDF,
            classifyFile("/storage/emulated/0/Download/invoice.pdf?token=123"),
        )
        assertEquals(BayFileKind.OTHER, classifyFile("firmware.bin"))
        assertEquals(BayFileKind.OTHER, classifyFile(".gitignore"))
        assertEquals(BayFileKind.OTHER, classifyFile(""))
    }

    @Test
    fun testStripLeadingEmoji() {
        // 标准单字符 Emoji 与空格
        assertEquals("快速传输直达台", stripLeadingEmoji("⚡ 快速传输直达台"))
        assertEquals("闲聊摸鱼", stripLeadingEmoji("☕ 闲聊摸鱼"))
        assertEquals("已收发文件记录", stripLeadingEmoji("✅ 已收发文件记录"))

        // 含 VS16（U+FE0F）的复合 Emoji
        assertEquals("图片", stripLeadingEmoji("🖼️ 图片"))
        assertEquals("系统与节点", stripLeadingEmoji("🛠️ 系统与节点"))
        assertEquals("注意", stripLeadingEmoji("⚠️注意"))

        // 前置空格与连续多个 Emoji
        assertEquals("多表情标题", stripLeadingEmoji("  🚀✨ 多表情标题"))
        assertEquals("", stripLeadingEmoji("🎉🚀"))

        // 零宽连接符（ZWJ）序列、肤色修饰符、国旗、Keycap 序列
        assertEquals("家庭群", stripLeadingEmoji("👨‍👩‍👧‍👦 家庭群"))
        assertEquals("彩虹", stripLeadingEmoji("🏳️‍🌈 彩虹"))
        assertEquals("收到", stripLeadingEmoji("👍🏽 收到"))
        assertEquals("中国", stripLeadingEmoji("🇨🇳 中国"))
        assertEquals("第一步", stripLeadingEmoji("1️⃣ 第一步"))
        assertEquals("标签", stripLeadingEmoji("#️⃣ 标签"))

        // 普通中文、英文、数字、符号与中间/尾部符号绝不受影响
        assertEquals("普通中文标题", stripLeadingEmoji("普通中文标题"))
        assertEquals("BayChat 2.0", stripLeadingEmoji("BayChat 2.0"))
        assertEquals("123 数字开头", stripLeadingEmoji("123 数字开头"))
        assertEquals("#1 热门房间", stripLeadingEmoji("#1 热门房间"))
        assertEquals("*重点提示", stripLeadingEmoji("*重点提示"))
        assertEquals("© 2026 BayChat", stripLeadingEmoji("© 2026 BayChat"))
        assertEquals("  保留缩进", stripLeadingEmoji("  保留缩进"))
        assertEquals("允许串门 ✓", stripLeadingEmoji("允许串门 ✓"))
        assertEquals("房间 🔥 很火", stripLeadingEmoji("房间 🔥 很火"))
        assertEquals("", stripLeadingEmoji(""))
    }
}
