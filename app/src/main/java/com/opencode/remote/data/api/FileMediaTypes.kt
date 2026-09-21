package com.opencode.remote.data.api

/** 文件类型路由：图片走 Coil 应用内看，PDF 应用内渲染，其余扔系统应用。 */
object FileMediaTypes {
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg")
    private const val PDF_EXT = "pdf"

    private val MIME_MAP = mapOf(
        // images
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "svg" to "image/svg+xml",
        // video
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv", "m4v" to "video/x-m4v",
        "ts" to "video/mp2t",
        // audio
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "m4a" to "audio/mp4", "flac" to "audio/flac", "aac" to "audio/aac",
        "opus" to "audio/opus",
        // documents
        "pdf" to "application/pdf",
        "epub" to "application/epub+zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain",
        "csv" to "text/csv", "json" to "application/json",
        // archives
        "zip" to "application/zip", "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar",
        "gz" to "application/gzip",
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean = extensionOf(name) in IMAGE_EXTS

    fun isPdf(name: String): Boolean = extensionOf(name) == PDF_EXT

    fun mimeFor(name: String): String =
        MIME_MAP[extensionOf(name)] ?: "*/*"
}
