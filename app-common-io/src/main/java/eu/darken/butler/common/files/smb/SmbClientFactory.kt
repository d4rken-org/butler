package eu.darken.butler.common.files.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Indirection so the connection pool can be driven without a real server in tests. */
fun interface SmbClientFactory {
    fun create(config: SmbConfig): SMBClient
}

@Module
@InstallIn(SingletonComponent::class)
object SmbClientFactoryModule {

    @Provides
    @Singleton
    fun clientFactory(): SmbClientFactory = SmbClientFactory { SMBClient(it) }
}
