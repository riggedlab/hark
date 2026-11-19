package io.github.riggedlab.hark

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class ScanFileStamp(val modified: FileTime, val length: Long, val fileKey: Any?)

/** 文件标识也参与比较：应用用 rename 替换 JSON，大小和修改时间可能相同。 */
internal fun File.scanStamp(): ScanFileStamp? = try {
    val attrs = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
    ScanFileStamp(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey())
} catch (_: IOException) {
    null
}
