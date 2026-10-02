package eu.darken.butler.common.files.sftp

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.ssh.MinaSftpConnector
import eu.darken.ssh.SftpConnector
import java.io.Closeable
import javax.inject.Singleton

/** A connector plus the SSH client behind it; closing it disconnects every session it opened. */
interface SftpClient : SftpConnector, Closeable

fun interface SftpClientFactory {
    fun create(): SftpClient
}

private class MinaSftpClient(
    private val connector: MinaSftpConnector = MinaSftpConnector(),
) : SftpClient, SftpConnector by connector {
    override fun close() = connector.close()
}

@Module
@InstallIn(SingletonComponent::class)
object SftpClientFactoryModule {
    @Provides
    @Singleton
    fun clientFactory(): SftpClientFactory = SftpClientFactory { MinaSftpClient() }
}
