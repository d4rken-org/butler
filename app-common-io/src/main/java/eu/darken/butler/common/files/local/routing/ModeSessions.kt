package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.io.callbacks
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.service.IsolatedServiceClient
import eu.darken.butler.common.files.local.service.IsolatedServiceClient.ServiceBindException
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.sharedresource.KeepAlive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import okio.FileHandle

class ModeSession(
    val mode: AccessMode,
    val ops: FileSystemOps<LocalPath, LocalPathLookup>,
    val batch: ClientBatchOps?,
    private val lease: KeepAlive?,
) : AutoCloseable {
    private val lock = Any()
    private var retainedLeases = 0
    private var closeRequested = false
    private var leaseClosed = false

    fun retainLeaseFor(stream: InputStream): InputStream {
        val retained = retainLease() ?: return stream
        return object : FilterInputStream(stream) {
            override fun close() = closePreservingSuppressed(
                { super.close() },
                { retained.close() },
            )
        }
    }

    fun retainLeaseFor(stream: OutputStream): OutputStream {
        val retained = retainLease() ?: return stream
        return object : FilterOutputStream(stream) {
            override fun close() = closePreservingSuppressed(
                { super.close() },
                { retained.close() },
            )
        }
    }

    fun retainLeaseFor(handle: FileHandle): FileHandle {
        val retained = retainLease() ?: return handle
        return handle.callbacks { retained.close() }
    }

    override fun close() {
        val closeNow = synchronized(lock) {
            closeRequested = true
            if (lease != null && retainedLeases == 0 && !leaseClosed) {
                leaseClosed = true
                lease
            } else {
                null
            }
        }
        closeNow?.close()
    }

    private fun retainLease(): Closeable? = synchronized(lock) {
        if (lease == null || leaseClosed) {
            null
        } else {
            retainedLeases++
            RetainedLease()
        }
    }

    private inner class RetainedLease : Closeable {
        private var closed = false

        override fun close() {
            val closeNow = synchronized(lock) {
                if (closed) return
                closed = true
                retainedLeases--
                if (closeRequested && retainedLeases == 0 && !leaseClosed) {
                    leaseClosed = true
                    lease
                } else {
                    null
                }
            }
            closeNow?.close()
        }
    }
}

private fun closePreservingSuppressed(vararg closeables: () -> Unit) {
    var thrown: Throwable? = null
    closeables.forEach { close ->
        try {
            close()
        } catch (t: Throwable) {
            thrown?.addSuppressed(t) ?: run { thrown = t }
        }
    }
    thrown?.let { throw it }
}

class ModeSessionRegistry(
    private val factory: ModeSessionFactory,
) : AutoCloseable {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<AccessMode, ModeSession>()

    suspend fun getOrOpen(mode: AccessMode): ModeSession = mutex.withLock {
        sessions[mode]?.let { return@withLock it }

        val session = factory.open(mode)
        sessions[mode] = session
        session
    }

    suspend fun forget(mode: AccessMode) = mutex.withLock {
        sessions.remove(mode)?.close()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}

class ModeSessionFactory @Inject constructor(
    private val fileSystemOps: LocalFileSystemOps,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val isolatedServiceClient: IsolatedServiceClient,
) {
    suspend fun open(mode: AccessMode): ModeSession = when (mode) {
        AccessMode.DIRECT -> directSession()
        AccessMode.ISOLATED -> openIsolatedOrDirectFallback()
        AccessMode.ROOT -> openRoot()
        AccessMode.ADB -> openAdb()
    }

    private fun directSession(): ModeSession = ModeSession(
        mode = AccessMode.DIRECT,
        ops = fileSystemOps,
        batch = null,
        lease = null,
    )

    private suspend fun openIsolatedOrDirectFallback(): ModeSession = try {
        val lease = isolatedServiceClient.get()
        val client = lease.item.fileOpsClient
        ModeSession(
            mode = AccessMode.ISOLATED,
            ops = client,
            batch = FileOpsClientBatchOps(client),
            lease = lease,
        )
    } catch (_: ServiceBindException) {
        directSession()
    }

    private suspend fun openRoot(): ModeSession {
        if (!rootManager.canUseRootNow()) throw RootUnavailableException()
        val lease = rootManager.serviceClient.get()
        val client = lease.item.clientModules.single { it is FileOpsClient } as FileOpsClient
        return ModeSession(
            mode = AccessMode.ROOT,
            ops = client,
            batch = FileOpsClientBatchOps(client),
            lease = lease,
        )
    }

    private suspend fun openAdb(): ModeSession {
        if (!adbManager.canUseAdbNow()) throw AdbUnavailableException()
        val lease = adbManager.serviceClient.get()
        val client = lease.item.clientModules.single { it is FileOpsClient } as FileOpsClient
        return ModeSession(
            mode = AccessMode.ADB,
            ops = client,
            batch = FileOpsClientBatchOps(client),
            lease = lease,
        )
    }
}

private class FileOpsClientBatchOps(
    private val client: FileOpsClient,
) : ClientBatchOps {
    override suspend fun copySubtreeExact(
        sourceRoot: LocalPath,
        destinationRoot: LocalPath,
        options: CopyAction.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> =
        // Exact-root semantics are equivalent to the legacy whole-operation IPC call only while
        // destinationRoot does not exist. The generic batch processor revalidates that before each try.
        client.copy(setOf(sourceRoot), destinationRoot, onIssue, options)

    override suspend fun moveSubtreeExact(
        sourceRoot: LocalPath,
        destinationRoot: LocalPath,
        options: MoveAction.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> =
        // See copySubtreeExact: callers must guarantee destinationRoot is absent before invoking this.
        client.move(setOf(sourceRoot), destinationRoot, onIssue, options)

    override suspend fun deleteSubtree(
        root: LocalPath,
        options: DeleteAction.Options<LocalPath>,
    ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> =
        client.delete(setOf(root), options)
}
