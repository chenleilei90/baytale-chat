package com.baytale.baychat.core.util

import java.util.Locale

// 文件类型分类（与 BayFileTypeColors 配色严格对应）
enum class BayFileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    PDF,
    DOCUMENT,
    APK,
    OTHER,
}

private val ARCHIVE_EXTS = setOf(
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "iso", "cab",
)

private val ARCHIVE_MIMES = setOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-rar-compressed",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-tar",
    "application/gzip",
    "application/x-gzip",
    "application/x-bzip2",
    "application/x-xz",
)

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "raw", "dng",
)

private val VIDEO_EXTS = setOf(
    "mp4", "mov", "mkv", "avi", "webm", "flv", "m4v", "wmv", "3gp", "ts", "mts",
)

private val AUDIO_EXTS = setOf(
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "mid", "midi", "amr",
)

private val DOC_EXTS = setOf(
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "markdown",
    "csv", "rtf", "odt", "ods", "odp", "pages", "numbers", "key",
    "json", "xml", "yaml", "yml", "log", "epub",
)

private val DOC_MIMES = setOf(
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/rtf",
    "application/json",
    "application/xml",
)

/**
 * 根据文件名扩展名与 MIME 类型识别文件所属分类，用于文件图标与记录列表分组。
 */
fun classifyFile(fileName: String, mimeType: String? = null): BayFileKind {
    // 剥离路径与 URL 查询参数，避免干扰扩展名判断
    val cleanName = fileName.substringBefore('?').substringBefore('#').trim()
    val baseName = cleanName.substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = baseName.lastIndexOf('.')
    val ext = if (dotIndex > 0 && dotIndex < baseName.lastIndex) {
        baseName.substring(dotIndex + 1).lowercase(Locale.ROOT)
    } else {
        ""
    }
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()

    return when {
        ext == "apk" || ext == "apks" || ext == "xapk" ||
            mime == "application/vnd.android.package-archive" -> BayFileKind.APK

        ext == "pdf" || mime == "application/pdf" -> BayFileKind.PDF

        ext in ARCHIVE_EXTS || mime in ARCHIVE_MIMES -> BayFileKind.ARCHIVE

        ext in IMAGE_EXTS || mime.startsWith("image/") -> BayFileKind.IMAGE

        ext in VIDEO_EXTS || mime.startsWith("video/") -> BayFileKind.VIDEO

        ext in AUDIO_EXTS || mime.startsWith("audio/") -> BayFileKind.AUDIO

        ext in DOC_EXTS || mime.startsWith("text/") || mime in DOC_MIMES -> BayFileKind.DOCUMENT

        else -> BayFileKind.OTHER
    }
}

/**
 * 将本地文件类型映射为服务端纸飞机元数据的精简分类标识（image / video / audio / archive / doc / apk / file）。
 */
fun classifyPlaneKind(fileName: String, mimeType: String? = null): String {
    return when (classifyFile(fileName, mimeType)) {
        BayFileKind.IMAGE -> "image"
        BayFileKind.VIDEO -> "video"
        BayFileKind.AUDIO -> "audio"
        BayFileKind.ARCHIVE -> "archive"
        BayFileKind.PDF, BayFileKind.DOCUMENT -> "doc"
        BayFileKind.APK -> "apk"
        BayFileKind.OTHER -> "file"
    }
}
