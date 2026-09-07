package eu.darken.butler.saftestprovider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class TestDocumentsProvider : DocumentsProvider() {
    private data class Node(
        val id: String,
        var parentId: String?,
        var name: String?,
        val mimeType: String,
    ) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    private val lock = Any()
    private val nodes = linkedMapOf<String, Node>()
    private val folders = linkedMapOf<String, String>()
    private val loading = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private val counters = linkedMapOf<String, Long>()
    private val journal = mutableListOf<String>()
    private var session = ""
    private var nextId = 1000L
    private val storage: File get() = File(requireNotNull(context).filesDir, "documents")
    private val resolver get() = requireNotNull(context).contentResolver

    override fun onCreate(): Boolean = synchronized(lock) {
        seed(resetContents = false)
        true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = synchronized(lock) {
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            addRow(columnNames.map { column ->
                when (column) {
                    Root.COLUMN_ROOT_ID -> ROOT_ID
                    Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                    Root.COLUMN_TITLE -> requireNotNull(context).getString(R.string.saf_test_provider_name)
                    Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                    Root.COLUMN_MIME_TYPES -> "*/*"
                    else -> null
                }
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = synchronized(lock) {
        record("queryDocument", documentId)
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(node(documentId)) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = synchronized(lock) {
        record("queryChildDocuments", parentDocumentId)
        directory(parentDocumentId)
        val children = nodes.values.filter { it.parentId == parentDocumentId }
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            val visible = if (parentDocumentId in loading) children.take(1) else children
            visible.forEach { addDocument(it) }
            extras = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, parentDocumentId in loading)
                errors[parentDocumentId]?.let { putString(DocumentsContract.EXTRA_ERROR, it) }
            }
            setNotificationUri(resolver, childrenUri(parentDocumentId))
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = synchronized(lock) {
        record("isChildDocument", documentId, parentDocumentId)
        if (nodes[parentDocumentId]?.isDirectory != true) return@synchronized false
        var ancestor = nodes[documentId]?.parentId
        while (ancestor != null) {
            if (ancestor == parentDocumentId) return@synchronized true
            ancestor = nodes[ancestor]?.parentId
        }
        false
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = synchronized(lock) {
        record("openDocument", documentId)
        signal?.throwIfCanceled()
        if (node(documentId).isDirectory) throw FileNotFoundException("Directory: $documentId")
        ParcelFileDescriptor.open(contentFile(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        synchronized(lock) {
            record("createDocument", parentDocumentId)
            directory(parentDocumentId)
            val created = Node(freshId(), parentDocumentId, displayName, mimeType)
            if (!created.isDirectory) contentFile(created.id).writeBytes(byteArrayOf())
            nodes[created.id] = created
            notifyChildren(parentDocumentId)
            created.id
        }

    override fun deleteDocument(documentId: String) = synchronized(lock) {
        record("deleteDocument", documentId)
        val target = mutableNode(documentId)
        removeSubtree(target)
        notifyChildren(requireNotNull(target.parentId))
    }

    override fun renameDocument(documentId: String, displayName: String): String = synchronized(lock) {
        record("renameDocument", documentId)
        val target = mutableNode(documentId)
        val renamed = target.copy(id = freshId(), name = displayName)
        if (!target.isDirectory && !contentFile(documentId).renameTo(contentFile(renamed.id))) {
            throw FileNotFoundException("Could not rename $documentId")
        }
        nodes.remove(documentId)
        nodes[renamed.id] = renamed
        nodes.values.filter { it.parentId == documentId }.forEach { it.parentId = renamed.id }
        if (loading.remove(documentId)) loading.add(renamed.id)
        errors.remove(documentId)?.let { errors[renamed.id] = it }
        notifyChildren(requireNotNull(target.parentId))
        if (target.isDirectory) notifyChildren(documentId)
        renamed.id
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method !in CONTROL_METHODS) return super.call(method, arg, extras)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && uid != SHELL_UID) {
            throw SecurityException("Fixture controls require shell or self UID")
        }
        return synchronized(lock) {
            when (method) {
                "stats" -> stats(extras)
                "reset" -> {
                    val previousFolders = nodes.values.filter { it.isDirectory }.map { it.id }
                    seed(resetContents = true)
                    (previousFolders + folders.values).distinct().forEach { notifyChildren(it) }
                    Bundle().apply { putString("session", session) }
                }
                else -> {
                    val key = extras?.getString("folder") ?: arg ?: error("Missing folder key")
                    val id = folders[key] ?: key.takeIf { nodes[it]?.isDirectory == true }
                        ?: error("Unknown folder: $key")
                    when (method) {
                        "setLoading" -> if (loading.add(id)) notifyChildren(id)
                        "completeLoading" -> if (loading.remove(id)) notifyChildren(id)
                        "setError" -> {
                            val message = requireNotNull(extras?.getString("message")) { "Missing message" }
                            if (errors.put(id, message) != message) notifyChildren(id)
                        }
                        "clearError" -> if (errors.remove(id) != null) notifyChildren(id)
                        "makeDuplicate" -> makeDuplicate(id, extras?.getString("name"))
                    }
                    Bundle().apply { putString("documentId", id) }
                }
            }
        }
    }

    private fun makeDuplicate(parentId: String, name: String?) {
        val children = nodes.values.filter { it.parentId == parentId && it.name != null }
        val original = if (name != null) children.firstOrNull { it.name == name } else children.firstOrNull()
        requireNotNull(original) { "No named child to duplicate" }
        if (children.count { it.name == original.name } >= 2) return
        val duplicate = original.copy(id = freshId())
        if (!duplicate.isDirectory) contentFile(duplicate.id).writeText("Injected duplicate of ${original.id}\n")
        nodes[duplicate.id] = duplicate
        notifyChildren(parentId)
    }

    private fun stats(extras: Bundle?): Bundle {
        val after = extras?.getLong("after", 0) ?: 0
        require(after >= 0 && after <= journal.size) { "after is outside this session's journal" }
        val limit = (extras?.getInt("limit", 200) ?: 200).coerceIn(1, 1000)
        val end = minOf(journal.size.toLong(), after + limit).toInt()
        return Bundle().apply {
            putString("session", session)
            putBundle("counters", Bundle().apply { counters.forEach { (method, count) -> putLong(method, count) } })
            counters.forEach { (method, count) -> putLong(method, count) }
            putString("journal", journal.subList(after.toInt(), end).joinToString("\n"))
            putLong("totalEntries", journal.size.toLong())
            putLong("nextAfter", end.toLong())
            putBoolean("hasMore", end < journal.size)
        }
    }

    private fun record(method: String, documentId: String, parentId: String? = null) {
        counters[method] = counters.getValue(method) + 1
        // Sequence, method, document ID, elapsed-realtime milliseconds, optional parent ID.
        journal.add("${journal.size + 1}\t$method\t$documentId\t${SystemClock.elapsedRealtime()}\t${parentId.orEmpty()}")
    }

    private fun MatrixCursor.addDocument(node: Node) {
        addRow(columnNames.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> node.id
                Document.COLUMN_DISPLAY_NAME -> node.name
                Document.COLUMN_MIME_TYPE -> node.mimeType
                Document.COLUMN_SIZE -> if (node.isDirectory) null else contentFile(node.id).length()
                Document.COLUMN_LAST_MODIFIED -> if (node.isDirectory) null else contentFile(node.id).lastModified()
                Document.COLUMN_FLAGS -> {
                    val mutationFlags = if (node.id in folders.values) 0 else {
                        Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                    }
                    mutationFlags or if (node.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
                }
                else -> null
            }
        })
    }

    private fun node(id: String): Node = nodes[id] ?: throw FileNotFoundException("Unknown document: $id")

    private fun directory(id: String): Node = node(id).also {
        if (!it.isDirectory) throw FileNotFoundException("Not a directory: $id")
    }

    private fun mutableNode(id: String): Node = node(id).also {
        if (id in folders.values) throw FileNotFoundException("Scenario folders are fixed: $id")
    }

    private fun removeSubtree(target: Node) {
        nodes.values.filter { it.parentId == target.id }.toList().forEach { removeSubtree(it) }
        if (!target.isDirectory && !contentFile(target.id).delete()) {
            throw FileNotFoundException("Could not delete ${target.id}")
        }
        nodes.remove(target.id)
        loading.remove(target.id)
        errors.remove(target.id)
    }

    private fun contentFile(id: String) = File(storage, id)
    private fun freshId() = (nextId++).toString()
    private fun childrenUri(id: String) = DocumentsContract.buildChildDocumentsUri(AUTHORITY, id)
    private fun notifyChildren(id: String) = resolver.notifyChange(childrenUri(id), null)

    private fun seed(resetContents: Boolean) {
        nodes.clear()
        folders.clear()
        loading.clear()
        errors.clear()
        nextId = 1000
        if (resetContents) check(storage.deleteRecursively()) { "Could not clear fixture contents" }
        check(storage.isDirectory || storage.mkdirs()) { "Could not create fixture storage" }

        fun folder(id: String, parent: String?, name: String, key: String) {
            nodes[id] = Node(id, parent, name, Document.MIME_TYPE_DIR)
            folders[key] = id
        }
        fun file(id: String, parent: String, name: String?, contents: String) {
            nodes[id] = Node(id, parent, name, "text/plain")
            if (resetContents || !contentFile(id).exists()) contentFile(id).writeText(contents)
        }

        folder("1", null, "Butler SAF test provider", "root")
        folder("10", "1", "plain", "plain")
        file("42", "10", "hello.txt", "Hello from opaque document 42.\n")
        file("43", "10", "other.txt", "Different contents from opaque document 43.\n")
        folder("20", "1", "duplicates", "duplicates")
        file("44", "20", "dup.txt", "First duplicate, document 44.\n")
        file("45", "20", "dup.txt", "Second duplicate, document 45.\n")
        file("46", "20", "other.txt", "Unique sibling of the duplicates.\n")
        folder("30", "1", "unnamed", "unnamed")
        file("47", "30", null, "This document has no display name.\n")
        file("48", "30", "visible.txt", "Visible sibling of the unnamed row.\n")
        folder("50", "1", "loading", "loading")
        file("51", "50", "partial.txt", "Visible while loading.\n")
        file("52", "50", "complete.txt", "Visible after completeLoading.\n")
        loading.add("50")
        folder("60", "1", "errored", "errored")
        folder("61", "60", "empty", "errored/empty")
        folder("62", "60", "partial", "errored/partial")
        file("63", "62", "partial.txt", "A row accompanied by EXTRA_ERROR.\n")
        errors["61"] = "Injected empty listing failure"
        errors["62"] = "Injected partial listing failure"
        folder("70", "1", "empty", "empty")
        folder("80", "1", "deep", "deep")
        folder("81", "80", "a", "deep/a")
        folder("82", "81", "b", "deep/a/b")
        folder("83", "82", "c", "deep/a/b/c")
        folder("84", "83", "d", "deep/a/b/c/d")
        file("85", "84", "file.txt", "Cold access from tree grant 82 must reach document 85.\n")
        folder("90", "1", "mutable", "mutable")
        file("91", "90", "rename-me.txt", "Renaming changes my ID and preserves my bytes.\n")
        // Dynamic documents are scoped to a process; never reuse their old backing contents.
        storage.listFiles()?.filter { it.name !in nodes }?.forEach { check(it.delete()) }
        counters.clear()
        JOURNALED_METHODS.forEach { counters[it] = 0 }
        journal.clear()
        session = UUID.randomUUID().toString()
    }

    companion object {
        const val AUTHORITY = "eu.darken.butler.saftestprovider.documents"
        private const val ROOT_ID = "1"
        private const val SHELL_UID = 2000
        private val CONTROL_METHODS = setOf(
            "reset", "setLoading", "completeLoading", "setError", "clearError", "makeDuplicate", "stats",
        )
        private val JOURNALED_METHODS = listOf(
            "queryChildDocuments", "queryDocument", "createDocument", "deleteDocument",
            "renameDocument", "openDocument", "isChildDocument",
        )
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}
