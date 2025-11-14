package eu.darken.butler.provider.documents

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for manual dependency injection.
 * ContentProviders don't support constructor injection, so we use this pattern.
 */
@InstallIn(SingletonComponent::class)
@EntryPoint
interface DocumentsProviderEntryPoint {
    fun inject(provider: ButlerDocumentsProvider)
}