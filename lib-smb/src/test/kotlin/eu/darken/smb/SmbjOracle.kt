package eu.darken.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileRenameInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Test-only reference backend. No production code depends on SMBJ. */
internal class SmbjOracle(private val config: SmbConfig = SmbConfig()) : SmbConnector {
    override suspend fun connect(endpoint: SmbEndpoint, credentials: SmbCredentials): SmbShare =
        withContext(Dispatchers.IO) {
            val client =
                SMBClient(
                    com.hierynomus.smbj.SmbConfig.builder()
                        .withDialects(
                            *config.dialects.map { SMB2Dialect.valueOf(it.name) }.toTypedArray()
                        )
                        .withSigningRequired(config.requireSigning)
                        .withEncryptData(config.requireEncryption)
                        .withDfsEnabled(false)
                        .withTimeout(
                            config.requestTimeout.inWholeMilliseconds,
                            TimeUnit.MILLISECONDS,
                        )
                        .withSoTimeout(config.requestTimeout.inWholeMilliseconds.toInt())
                        .build()
                )
            try {
                val connection = client.connect(endpoint.host, endpoint.port)
                val auth =
                    when (credentials) {
                        SmbCredentials.Guest -> AuthenticationContext.guest()
                        is SmbCredentials.Password ->
                            AuthenticationContext(
                                credentials.username,
                                credentials.password,
                                credentials.domain,
                            )
                    }
                val session =
                    try {
                        oracleCall(SmbException.Kind.AUTHENTICATION) {
                            connection.authenticate(auth)
                        }
                    } finally {
                        auth.password?.fill('\u0000')
                    }
                if (
                    credentials is SmbCredentials.Password &&
                        (session.isGuest || session.isAnonymous)
                ) {
                    throw SmbException(SmbException.Kind.AUTHENTICATION)
                }
                val share =
                    oracleCall(SmbException.Kind.SHARE_ACCESS_DENIED) {
                        session.connectShare(endpoint.share) as DiskShare
                    }
                object : SmbShare {
                    override val connected: Boolean
                        get() = connection.isConnected && share.isConnected

                    override val dialect: SmbDialect =
                        SmbDialect.valueOf(
                            connection.connectionContext.negotiatedProtocol.dialect.name
                        )

                    override suspend fun stat(path: SmbPath): SmbEntry = oracleCall {
                        val info = share.getFileInformation(path.wire)
                        SmbEntry(
                            path,
                            type(info.basicInformation.fileAttributes),
                            info.standardInformation.endOfFile,
                            instant(info.basicInformation.lastWriteTime),
                            instant(info.basicInformation.creationTime),
                        )
                    }

                    override fun list(path: SmbPath): Flow<SmbEntry> = flow {
                        oracleMap {
                            share
                                .openDirectory(
                                    path.wire,
                                    setOf(AccessMask.GENERIC_READ),
                                    null,
                                    SMB2ShareAccess.ALL,
                                    SMB2CreateDisposition.FILE_OPEN,
                                    setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE),
                                )
                                .use { directory ->
                                    for (entry in directory) {
                                        if (!SmbPath.isValidSegment(entry.fileName)) continue
                                        emit(
                                            SmbEntry(
                                                path.child(entry.fileName),
                                                type(entry.fileAttributes),
                                                entry.endOfFile,
                                                instant(entry.lastWriteTime),
                                                instant(entry.creationTime),
                                            )
                                        )
                                    }
                                }
                        }
                    }
                        .flowOn(Dispatchers.IO)

                    override suspend fun mkdir(path: SmbPath) = oracleCall {
                        share.mkdir(path.wire)
                    }

                    override suspend fun delete(path: SmbPath, recursive: Boolean) = oracleCall {
                        if (share.folderExists(path.wire)) share.rmdir(path.wire, recursive)
                        else share.rm(path.wire)
                    }

                    override suspend fun move(source: SmbPath, destination: SmbPath) = oracleCall {
                        share
                            .open(
                                source.wire,
                                setOf(AccessMask.DELETE, AccessMask.GENERIC_READ),
                                null,
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                emptySet(),
                            )
                            .use {
                                it.setFileInformation(
                                    FileRenameInformation(false, 0, destination.wire)
                                )
                            }
                    }

                    override suspend fun openFile(path: SmbPath, mode: SmbOpenMode): SmbFile =
                        oracleCall {
                            val access =
                                if (mode == SmbOpenMode.READ) setOf(AccessMask.GENERIC_READ)
                                else setOf(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE)
                            val disposition =
                                when (mode) {
                                    SmbOpenMode.READ -> SMB2CreateDisposition.FILE_OPEN
                                    SmbOpenMode.CREATE_NEW -> SMB2CreateDisposition.FILE_CREATE
                                    SmbOpenMode.OVERWRITE -> SMB2CreateDisposition.FILE_OVERWRITE_IF
                                    else -> SMB2CreateDisposition.FILE_OPEN_IF
                                }
                            val file =
                                share.openFile(
                                    path.wire,
                                    access,
                                    setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                    SMB2ShareAccess.ALL,
                                    disposition,
                                    setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                                )
                            object : SmbFile {
                                private val closed =
                                    java.util.concurrent.atomic.AtomicBoolean(false)

                                override fun blocking(): SmbBlockingFile =
                                    object : SmbBlockingFile {
                                        override fun read(
                                            offset: Long,
                                            buffer: ByteArray,
                                            start: Int,
                                            length: Int,
                                        ): Int =
                                            if (length == 0) 0
                                            else file.read(buffer, offset, start, length)

                                        override fun write(
                                            offset: Long,
                                            buffer: ByteArray,
                                            start: Int,
                                            length: Int,
                                        ) {
                                            var done = 0
                                            while (done < length) {
                                                val count =
                                                    file
                                                        .write(
                                                            buffer,
                                                            offset + done,
                                                            start + done,
                                                            length - done,
                                                        )
                                                        .toInt()
                                                check(count > 0)
                                                done += count
                                            }
                                        }

                                        override fun size(): Long = file.length

                                        override fun resize(size: Long) {
                                            file.length = size
                                        }

                                        override fun flush() = file.flush()

                                        override fun close() {
                                            if (closed.compareAndSet(false, true)) file.close()
                                        }
                                    }

                                override suspend fun read(
                                    offset: Long,
                                    buffer: ByteArray,
                                    start: Int,
                                    length: Int,
                                ): Int = oracleCall {
                                    if (length == 0) 0 else file.read(buffer, offset, start, length)
                                }

                                override suspend fun write(
                                    offset: Long,
                                    buffer: ByteArray,
                                    start: Int,
                                    length: Int,
                                ) = oracleCall {
                                    var done = 0
                                    while (done < length) {
                                        val written =
                                            file
                                                .write(
                                                    buffer,
                                                    offset + done,
                                                    start + done,
                                                    length - done,
                                                )
                                                .toInt()
                                        check(written > 0)
                                        done += written
                                    }
                                }

                                override suspend fun size(): Long = oracleCall { file.length }

                                override suspend fun resize(size: Long) = oracleCall {
                                    file.length = size
                                }

                                override suspend fun flush() = oracleCall { file.flush() }

                                override suspend fun close() = oracleCall {
                                    if (closed.compareAndSet(false, true)) file.close()
                                }
                            }
                        }

                    override suspend fun setModifiedAt(path: SmbPath, instant: Instant) =
                        oracleCall {
                            share.setFileInformation(
                                path.wire,
                                FileBasicInformation(
                                    FileBasicInformation.DONT_SET,
                                    FileBasicInformation.DONT_SET,
                                    FileTime.ofEpochMillis(instant.toEpochMilliseconds()),
                                    FileBasicInformation.DONT_SET,
                                    0,
                                ),
                            )
                        }

                    override suspend fun capacity(): SmbCapacity = oracleCall {
                        share.shareInformation.let { SmbCapacity(it.totalSpace, it.freeSpace) }
                    }

                    override fun disconnect() {
                        client.close()
                    }

                    override suspend fun close() = withContext(Dispatchers.IO) { client.close() }
                }
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        }
}

private val SmbPath.wire: String
    get() = segments.joinToString("\\")

private fun instant(time: FileTime): Instant = Instant.fromEpochMilliseconds(time.toEpochMillis())

private fun type(attributes: Long): SmbFileType =
    when {
        attributes and 0x400 != 0L -> SmbFileType.REPARSE_POINT
        attributes and 0x10 != 0L -> SmbFileType.DIRECTORY
        else -> SmbFileType.FILE
    }

private suspend fun <T> oracleCall(
    accessDenied: SmbException.Kind = SmbException.Kind.ACCESS_DENIED,
    block: suspend () -> T,
): T = withContext(Dispatchers.IO) { oracleMap(accessDenied, block) }

private suspend fun <T> oracleMap(
    accessDenied: SmbException.Kind = SmbException.Kind.ACCESS_DENIED,
    block: suspend () -> T,
): T {
    try {
        return block()
    } catch (error: SMBApiException) {
        val kind =
            when (error.statusCode) {
                0xc000000fL,
                0xc0000034L,
                0xc000003aL,
                0xc0000225L -> SmbException.Kind.MISSING
                0xc0000035L -> SmbException.Kind.ALREADY_EXISTS
                0xc0000022L -> accessDenied
                0xc000006dL -> SmbException.Kind.AUTHENTICATION
                0xc00000ccL -> SmbException.Kind.SHARE_MISSING
                0xc0000101L -> SmbException.Kind.DIRECTORY_NOT_EMPTY
                0xc0000103L -> SmbException.Kind.NOT_DIRECTORY
                0xc00000baL -> SmbException.Kind.IS_DIRECTORY
                0xc0000043L -> SmbException.Kind.SHARING_VIOLATION
                else -> SmbException.Kind.OTHER
            }
        throw SmbException(kind, error.statusCode, cause = error)
    }
}
