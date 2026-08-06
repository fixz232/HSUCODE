package com.hsucode.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File

/** Exposes the persistent files/ workspace to Android's system file picker. */
class HsucodeDocumentsProvider : DocumentsProvider() {
    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    )

    override fun onCreate(): Boolean {
        context?.let { WorkspaceManager.init(it) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        ))
        val root = WorkspaceManager.filesRoot() ?: return cursor
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "hsucode-files")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "HSUCODE 工作区")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_LOCAL_ONLY)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root.freeSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(this.projection).also { includeDocument(it, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(this.projection)
        val parent = resolve(parentDocumentId) ?: return cursor
        parent.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { includeDocument(cursor, idFor(it)) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = requireNotNull(resolve(documentId)) { "文件不存在" }
        val writing = mode.contains('w')
        val flags = if (writing) {
            // SAF callers expect "w" to replace the existing document, otherwise an
            // export can leave stale bytes at the end of a previously longer file.
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = requireNotNull(resolve(parentDocumentId)) { "目录不存在" }
        val target = safeChild(parent, displayName)
        require(!target.exists()) { "目标已存在" }
        if (mimeType == Document.MIME_TYPE_DIR) require(target.mkdirs()) else require(target.createNewFile())
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = requireNotNull(resolve(documentId)) { "文件不存在" }
        require(file.canonicalPath != parentRoot().canonicalPath) { "不能删除工作区根目录" }
        require(file.deleteRecursively()) { "删除失败" }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = requireNotNull(resolve(documentId)) { "文件不存在" }
        val target = safeChild(requireNotNull(source.parentFile), displayName)
        require(!target.exists() && source.renameTo(target)) { "重命名失败" }
        return idFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(parentDocumentId) ?: return false
        val child = resolve(documentId) ?: return false
        return child.canonicalPath.startsWith(parent.canonicalPath + File.separator)
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        val file = resolve(documentId) ?: return
        val isDir = file.isDirectory
        val flags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name.ifBlank { "HSUCODE 工作区" })
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mime(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun parentRoot(): File = requireNotNull(WorkspaceManager.filesRoot())
    private fun safeChild(parent: File, displayName: String): File {
        require(displayName.isNotBlank() && displayName != "." && displayName != "..") { "文件名不能为空" }
        require('/' !in displayName && '\\' !in displayName) { "文件名不能包含路径分隔符" }
        val canonicalParent = parent.canonicalFile
        val target = File(canonicalParent, displayName).canonicalFile
        require(target.parentFile?.path == canonicalParent.path) { "目标必须位于当前目录" }
        // The parent itself must still be inside files/ when a provider caller races a rename.
        WorkspaceFileOps.resolve(parentRoot(), relative(canonicalParent)).getOrThrow()
        return target
    }
    private fun resolve(id: String): File? = if (id == ROOT_ID) parentRoot() else {
        WorkspaceFileOps.resolve(parentRoot(), id).getOrNull()?.takeIf { it.exists() }
    }
    private fun idFor(file: File): String = parentRoot().canonicalFile.toPath()
        .relativize(file.canonicalFile.toPath())
        .toString()
        .replace(File.separatorChar, '/')
        .trimEnd('/')
    private fun relative(file: File): String = idFor(file)
    private fun mime(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    companion object { private const val ROOT_ID = "root" }
}
