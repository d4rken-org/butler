package eu.darken.butler.explorer.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.explorer.core.watcher.AndroidFileSystemWatcher
import eu.darken.butler.explorer.core.watcher.FileSystemWatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ExplorerModule {
    
    @Binds
    @Singleton
    abstract fun bindFileSystemWatcher(
        androidWatcher: AndroidFileSystemWatcher
    ): FileSystemWatcher
}