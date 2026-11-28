// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/**
 * Helpers for reaching a PDF that came from somewhere other than the library folder
 * (a browser, a file manager, the document picker) and for telling the same file apart
 * when it shows up through more than one address.
 */
internal object BookAccess {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private val storageRoots = listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/")

    enum class Access { OK, DENIED, MISSING }

    /** The "document id" part of a document address, whether or not it goes through a tree. */
    fun documentId(uri: Uri): String? {
        val segments = uri.pathSegments
        val index = segments.lastIndexOf("document")
        return if (index >= 0 && index + 1 < segments.size) segments[index + 1] else null
    }

    private fun treeId(uri: Uri): String? {
        val segments = uri.pathSegments
        return if (segments.size >= 2 && segments[0] == "tree") segments[1] else null
    }

    fun isTreeBased(uri: Uri): Boolean = treeId(uri) != null

    /**
     * A name for the file that does not depend on which app handed it over, for example
     * "primary:Download/book.pdf". Null when the address does not reveal where the file is.
     */
    fun canonicalKey(uri: Uri): String? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val root = storageRoots.firstOrNull { path.startsWith(it) } ?: return null
            return "primary:" + path.removePrefix(root)
        }
        if (uri.scheme != "content") return null
        val id = documentId(uri) ?: return null
        return when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> id
            DOWNLOADS_AUTHORITY -> if (id.startsWith("raw:")) {
                val path = id.removePrefix("raw:")
                val root = storageRoots.firstOrNull { path.startsWith(it) }
                if (root != null) "primary:" + path.removePrefix(root) else null
            } else null
            else -> null
        }
    }

    /**
     * Android only lets Kontho read a file it has been granted. A grant on the library folder
     * covers every file inside it, so a plain address for such a file is rewritten to go through
     * that folder grant. The grant on [preferredTree] (the saved library folder) wins when several match.
     * Anything that no folder grant covers is returned unchanged.
     */
    fun stableUri(context: Context, uri: Uri, preferredTree: Uri? = null): Uri {
        if (uri.scheme != "content" || isTreeBased(uri)) return uri
        val key = canonicalKey(uri) ?: return uri
        val trees = try {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission && it.uri.authority == EXTERNAL_STORAGE_AUTHORITY && isTreeBased(it.uri) }
                .map { it.uri }
        } catch (_: Exception) {
            emptyList()
        }
        val ordered = trees.sortedBy { if (it == preferredTree) 0 else 1 }
        for (tree in ordered) {
            val root = treeId(tree) ?: continue
            val covered = key == root || key.startsWith(if (root.endsWith("/")) root else "$root/") ||
                (root.endsWith(":") && key.startsWith(root))
            if (covered) {
                return try {
                    DocumentsContract.buildDocumentUriUsingTree(tree, key)
                } catch (_: Exception) {
                    uri
                }
            }
        }
        return uri
    }

    /** True when Android will still let Kontho read [uri] after the app restarts. */
    fun isDurable(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        if (isTreeBased(uri)) return true
        return try {
            context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
        } catch (_: Exception) {
            false
        }
    }

    /** Cheap check that the file can be opened right now. Runs file I/O: call off the main thread. */
    fun probe(context: Context, uri: Uri): Access = try {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        if (descriptor == null) {
            Access.MISSING
        } else {
            descriptor.close()
            Access.OK
        }
    } catch (error: Exception) {
        if (isPermissionFailure(error)) Access.DENIED else Access.MISSING
    }

    /**
     * "Permission Denial: opening provider ..." arrives as a SecurityException on most phones and
     * as a FileNotFoundException or IOException on others. All of them mean the same thing.
     */
    fun isPermissionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (current is SecurityException) return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("permission denial") || message.contains("permission denied") ||
                message.contains("eacces") || message.contains("requires that you obtain access")
            ) return true
            if (current is FileNotFoundException && message.contains("open failed") && message.contains("eperm")) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Collapses entries that are the same file reached through different addresses. Entries are
     * the same when their canonical key matches, or when they share a name and a known, non-zero
     * size. The entry that goes through a folder grant is kept, since it is the one that keeps working.
     */
    fun <T> dedupe(
        items: List<T>,
        uriOf: (T) -> Uri,
        nameOf: (T) -> String,
        sizeOf: (Uri) -> Long
    ): List<T> {
        if (items.size < 2) return items
        val removed = HashSet<Int>()

        fun better(a: Int, b: Int): Int {
            val aTree = isTreeBased(uriOf(items[a]))
            val bTree = isTreeBased(uriOf(items[b]))
            return if (bTree && !aTree) b else a
        }

        val byKey = HashMap<String, Int>()
        for (index in items.indices) {
            val key = canonicalKey(uriOf(items[index])) ?: continue
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = index
            } else {
                val keep = better(existing, index)
                removed.add(if (keep == existing) index else existing)
                byKey[key] = keep
            }
        }

        val byName = HashMap<String, MutableList<Int>>()
        for (index in items.indices) {
            if (index in removed) continue
            byName.getOrPut(nameOf(items[index]).trim().lowercase()) { mutableListOf() }.add(index)
        }
        for (group in byName.values) {
            if (group.size < 2) continue
            val keptBySize = HashMap<Long, Int>()
            for (index in group) {
                val size = sizeOf(uriOf(items[index]))
                if (size <= 0L) continue
                val existing = keptBySize[size]
                if (existing == null) {
                    keptBySize[size] = index
                } else {
                    val keep = better(existing, index)
                    removed.add(if (keep == existing) index else existing)
                    keptBySize[size] = keep
                }
            }
        }
        return items.filterIndexed { index, _ -> index !in removed }
    }
}
