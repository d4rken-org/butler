package eu.darken.butler.common.files.webdav.credentials

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavCredentialCipherModule {

    @Binds
    @Singleton
    abstract fun cipher(impl: KeystoreWebDavCredentialCipher): WebDavCredentialCipher
}
