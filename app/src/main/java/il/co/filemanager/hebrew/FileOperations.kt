package il.co.filemanager.hebrew

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun uniqueName(parent: DocumentFile, requested: String): String {
    if (parent.findFile(requested) == null) return requested
    val dot = requested.lastIndexOf('.')
    val base = if (dot > 0) requested.substring(0, dot) else requested
    val extension = if (dot > 0) requested.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = "$base ($index)$extension"
        if (parent.findFile(candidate) == null) return candidate
        index++
    }
}

fun copyDocumentTree(context: Context, source: DocumentFile, destination: DocumentFile, onProgress: (String) -> Unit): DocumentFile {
    if (!destination.isDirectory) throw IOException("תיקיית היעד אינה תקינה")
    if (source.isDirectory && containsDocument(source, destination.uri)) {
        throw IOException("לא ניתן להעתיק תיקייה לתוך עצמה או לתוך אחת מתיקיותיה")
    }
    if (source.uri == destination.uri) throw IOException("מקור ויעד זהים")
    return copyOne(context, source, destination, onProgress)
}

private fun containsDocument(directory: DocumentFile, target: android.net.Uri): Boolean {
    if (directory.uri == target) return true
    if (!directory.isDirectory) return false
    return directory.listFiles().any { child ->
        child.uri == target || (child.isDirectory && containsDocument(child, target))
    }
}

private fun copyOne(context: Context, source: DocumentFile, destination: DocumentFile, onProgress: (String) -> Unit): DocumentFile {
    val originalName = source.name ?: "קובץ"
    val name = uniqueName(destination, originalName)
    if (source.isDirectory) {
        val newDirectory = destination.createDirectory(name) ?: throw IOException("לא ניתן ליצור תיקייה: $name")
        source.listFiles().forEach { child ->
            onProgress("מעתיק: ${child.name ?: "פריט"}")
            copyOne(context, child, newDirectory, onProgress)
        }
        return newDirectory
    }
    val output = destination.createFile(source.type ?: "application/octet-stream", name)
        ?: throw IOException("לא ניתן ליצור קובץ: $name")
    try {
        val inputStream = context.contentResolver.openInputStream(source.uri)
            ?: throw IOException("לא ניתן לקרוא את הקובץ: $originalName")
        val outputStream = context.contentResolver.openOutputStream(output.uri, "w")
            ?: throw IOException("לא ניתן לכתוב את הקובץ: $name")
        inputStream.use { input -> outputStream.use { target -> input.copyTo(target, 64 * 1024) } }
    } catch (error: Exception) {
        output.delete()
        throw error
    }
    return output
}

fun createZipFromDocument(context: Context, source: DocumentFile, destination: DocumentFile, onProgress: (String) -> Unit): DocumentFile {
    val sourceName = source.name ?: "קבצים"
    val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "קבצים" }
    val zipFile = destination.createFile("application/zip", uniqueName(destination, "$base.zip"))
        ?: throw IOException("לא ניתן ליצור קובץ ZIP")
    try {
        val output = context.contentResolver.openOutputStream(zipFile.uri, "w")
            ?: throw IOException("לא ניתן לפתוח את קובץ ה-ZIP לכתיבה")
        ZipOutputStream(output.buffered()).use { zip ->
            val rootName = if (source.isDirectory) "$sourceName/" else sourceName
            addToZip(context, source, rootName, zip, onProgress)
        }
    } catch (error: Exception) {
        zipFile.delete()
        throw error
    }
    return zipFile
}

private fun addToZip(context: Context, document: DocumentFile, entryName: String, zip: ZipOutputStream, onProgress: (String) -> Unit) {
    if (document.isDirectory) {
        val directoryName = if (entryName.endsWith("/")) entryName else "$entryName/"
        zip.putNextEntry(ZipEntry(directoryName))
        zip.closeEntry()
        document.listFiles().forEach { child ->
            addToZip(context, child, directoryName + (child.name ?: "פריט"), zip, onProgress)
        }
    } else {
        onProgress("דוחס: ${document.name ?: "קובץ"}")
        zip.putNextEntry(ZipEntry(entryName))
        val input = context.contentResolver.openInputStream(document.uri)
            ?: throw IOException("לא ניתן לקרוא את ${document.name}")
        input.use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }
}

fun listZipEntries(context: Context, archive: DocumentFile): List<String> {
    val input = context.contentResolver.openInputStream(archive.uri) ?: throw IOException("לא ניתן לפתוח את הארכיון")
    val names = mutableListOf<String>()
    ZipInputStream(input.buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null && names.size < 5000) {
            names += (if (entry.isDirectory) "📁 " else "📄 ") + entry.name
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return names
}

fun extractZipSafely(context: Context, archive: DocumentFile, destination: DocumentFile, onProgress: (String) -> Unit): DocumentFile {
    if (!destination.isDirectory) throw IOException("תיקיית היעד אינה תקינה")
    val archiveName = archive.name ?: "ארכיון.zip"
    val suggestedFolder = archiveName.substringBeforeLast('.', archiveName).ifBlank { "חילוץ" }
    val targetRoot = destination.createDirectory(uniqueName(destination, suggestedFolder))
        ?: throw IOException("לא ניתן ליצור תיקיית חילוץ")
    var totalBytes = 0L
    var entriesCount = 0
    val input = context.contentResolver.openInputStream(archive.uri) ?: throw IOException("לא ניתן לקרוא את הארכיון")
    ZipInputStream(input.buffered()).use { zip ->
        var entry = zip.nextEntry
        val buffer = ByteArray(32 * 1024)
        while (entry != null) {
            entriesCount++
            if (entriesCount > 10000) throw IOException("הארכיון מכיל יותר מדי פריטים")
            val normalized = entry.name.replace(92.toChar(), '/')
            if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
                zip.closeEntry()
                entry = zip.nextEntry
                continue
            }
            val parts = normalized.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.contains(':') }) {
                zip.closeEntry()
                entry = zip.nextEntry
                continue
            }
            var parent = targetRoot
            for (part in parts.dropLast(1)) {
                parent = parent.findFile(part)?.takeIf { it.isDirectory }
                    ?: parent.createDirectory(part)
                    ?: throw IOException("לא ניתן ליצור תיקייה בחילוץ")
            }
            if (entry.isDirectory) {
                parent.findFile(parts.last())?.takeIf { it.isDirectory } ?: parent.createDirectory(parts.last())
            } else {
                val outFile = parent.createFile("application/octet-stream", uniqueName(parent, parts.last()))
                    ?: throw IOException("לא ניתן ליצור קובץ בחילוץ")
                onProgress("מחלץ: ${parts.last()}")
                val output = context.contentResolver.openOutputStream(outFile.uri, "w")
                    ?: throw IOException("לא ניתן לכתוב קובץ בחילוץ")
                var entryBytes = 0L
                try {
                    output.use { target ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count <= 0) break
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > 100L * 1024 * 1024 || totalBytes > 500L * 1024 * 1024) {
                                throw IOException("החילוץ נעצר: הארכיון חורג ממגבלת הגודל הבטוחה")
                            }
                            target.write(buffer, 0, count)
                        }
                    }
                } catch (error: Exception) {
                    outFile.delete()
                    throw error
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return targetRoot
}
