package org.kontho.pdfreader

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.IOException

internal data class PdfLinkRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String
)

internal data class PdfLinkSnapshot(
    val linksByPage: Map<Int, List<PdfLinkRegion>>
)

internal object PdfLinkSupport {
    /**
     * Read only external web-link annotations from a PDF. Comment/note
     * annotations are intentionally not loaded or exposed by the reader.
     */
    fun read(resolver: ContentResolver, uri: Uri, password: String? = null): PdfLinkSnapshot {
        val links = mutableMapOf<Int, MutableList<PdfLinkRegion>>()
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not read PDF links.")
        input.use {
            PDDocument.load(it, password.orEmpty(), MemoryUsageSetting.setupTempFileOnly()).use { document ->
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
                    }
                }
            }
        }
        return PdfLinkSnapshot(links.mapValues { it.value.toList() })
    }
}
