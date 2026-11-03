package org.kontho.pdfreader

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File
import java.io.FileInputStream
import java.io.IOException

internal data class PdfLinkRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String
)

internal data class PendingPdfComment(
    val pageIndex: Int,
    val text: String
)

internal data class PdfMarkupSnapshot(
    val linksByPage: Map<Int, List<PdfLinkRegion>>,
    val commentsByPage: Map<Int, List<String>>
)

internal object PdfMarkupSupport {
    fun read(resolver: ContentResolver, uri: Uri): PdfMarkupSnapshot {
        val links = mutableMapOf<Int, MutableList<PdfLinkRegion>>()
        val comments = mutableMapOf<Int, MutableList<String>>()
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not read PDF annotations.")
        input.use {
            PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val box = page.mediaBox
                    val pageWidth = box.width.coerceAtLeast(1f)
                    val pageHeight = box.height.coerceAtLeast(1f)
                    for (annotation in page.annotations) {
                        val rectangle = annotation.rectangle
                        if (annotation is PDAnnotationLink && rectangle != null) {
                            val action = annotation.action as? PDActionURI
                            val target = action?.uri?.trim().orEmpty()
                            if (target.startsWith("https://", true) || target.startsWith("http://", true)) {
                                val left = ((rectangle.lowerLeftX - box.lowerLeftX) / pageWidth).coerceIn(0f, 1f)
                                val right = ((rectangle.upperRightX - box.lowerLeftX) / pageWidth).coerceIn(0f, 1f)
                                val top = ((box.upperRightY - rectangle.upperRightY) / pageHeight).coerceIn(0f, 1f)
                                val bottom = ((box.upperRightY - rectangle.lowerLeftY) / pageHeight).coerceIn(0f, 1f)
                                if (right > left && bottom > top) {
                                    links.getOrPut(pageIndex) { mutableListOf() }.add(
                                        PdfLinkRegion(left, top, right, bottom, target)
                                    )
                                }
                            }
                        }
                        if (annotation is PDAnnotationText) {
                            val note = annotation.contents?.trim().orEmpty()
                            if (note.isNotBlank()) {
                                comments.getOrPut(pageIndex) { mutableListOf() }.add(note)
                            }
                        }
                    }
                }
            }
        }
        return PdfMarkupSnapshot(
            links.mapValues { it.value.toList() },
            comments.mapValues { it.value.toList() }
        )
    }

    /**
     * Writes text-note annotations to a new temporary PDF first. When the
     * destination is the source itself, a full backup is kept until the
     * replacement stream has completed so a provider write failure can be
     * rolled back rather than leaving a truncated original.
     */
    fun saveComments(
        resolver: ContentResolver,
        sourceUri: Uri,
        destinationUri: Uri,
        cacheDirectory: File,
        comments: List<PendingPdfComment>
    ) {
        if (comments.isEmpty()) throw IOException("There are no new comments to save.")
        val token = System.nanoTime().toString()
        val originalBackup = File(cacheDirectory, "kontho-original-$token.pdf")
        val modifiedFile = File(cacheDirectory, "kontho-commented-$token.pdf")
        try {
            val source = resolver.openInputStream(sourceUri)
                ?: throw IOException("Could not read the original PDF.")
            source.use { input ->
                originalBackup.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }

            PDDocument.load(FileInputStream(originalBackup), MemoryUsageSetting.setupTempFileOnly()).use { document ->
                comments.forEach { comment ->
                    if (comment.pageIndex !in 0 until document.numberOfPages) {
                        throw IOException("A comment refers to a page that no longer exists.")
                    }
                    val page = document.getPage(comment.pageIndex)
                    val box = page.mediaBox
                    val iconSize = 28f
                    val x = (box.upperRightX - iconSize - 12f).coerceAtLeast(box.lowerLeftX)
                    val y = (box.upperRightY - iconSize - 12f).coerceAtLeast(box.lowerLeftY)
                    val annotation = PDAnnotationText().apply {
                        contents = comment.text
                        title = "Kontho PDF Reader"
                        name = PDAnnotationText.NAME_COMMENT
                        open = false
                        rectangle = PDRectangle(x, y, iconSize, iconSize)
                        setPage(page)
                    }
                    page.annotations.add(annotation)
                }
                document.save(modifiedFile)
            }

            // Re-open the result before touching the selected destination.
            PDDocument.load(FileInputStream(modifiedFile), MemoryUsageSetting.setupTempFileOnly()).use { check ->
                if (check.numberOfPages <= 0) throw IOException("The commented PDF could not be validated.")
            }

            val replacingSource = sourceUri == destinationUri
            var replacementStarted = false
            try {
                replacementStarted = true
                val output = resolver.openOutputStream(destinationUri, "wt")
                    ?: throw IOException("Android did not provide write access to the selected PDF.")
                output.use { stream ->
                    modifiedFile.inputStream().use { input -> input.copyTo(stream, 64 * 1024) }
                    stream.flush()
                }
            } catch (writeError: Exception) {
                if (replacingSource && replacementStarted) {
                    try {
                        val restore = resolver.openOutputStream(destinationUri, "wt")
                            ?: throw IOException("Could not reopen the original PDF for recovery.")
                        restore.use { stream ->
                            originalBackup.inputStream().use { input -> input.copyTo(stream, 64 * 1024) }
                            stream.flush()
                        }
                    } catch (restoreError: Exception) {
                        throw IOException(
                            "Saving comments failed, and Android could not restore the original file. Keep the backup at ${originalBackup.name} and do not delete it.",
                            restoreError
                        )
                    }
                }
                throw IOException(
                    if (replacingSource) "Could not replace the original PDF. The original file was restored."
                    else "Could not save the commented copy.",
                    writeError
                )
            }
        } finally {
            modifiedFile.delete()
            // Keep the original backup only when rollback itself failed; that
            // path reports its filename to the user for manual recovery.
            if (modifiedFile.exists()) modifiedFile.delete()
            // The backup is a temporary safety copy, not a permanent duplicate.
            originalBackup.delete()
        }
    }
}
