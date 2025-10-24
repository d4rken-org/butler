package eu.darken.butler.common.files.metadata

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Dagger module for metadata extraction infrastructure.
 *
 * Binds all [MetadataExtractor] implementations into a set
 * that's injected into [MetadataRepo].
 */
@Module
@InstallIn(SingletonComponent::class)
object MetadataModule {

    /**
     * Bind APK metadata extractor into the set of extractors.
     */
    @Provides
    @IntoSet
    fun bindApkExtractor(extractor: ApkMetadataExtractor): MetadataExtractor<*> = extractor

    /**
     * Bind image metadata extractor into the set of extractors.
     */
    @Provides
    @IntoSet
    fun bindImageExtractor(extractor: ImageMetadataExtractor): MetadataExtractor<*> = extractor

    // Future extractors can be added here:
    // @Provides @IntoSet
    // fun bindVideoExtractor(extractor: VideoMetadataExtractor): @JvmSuppressWildcards MetadataExtractor<*> = extractor
    //
    // @Provides @IntoSet
    // fun bindAudioExtractor(extractor: AudioMetadataExtractor): @JvmSuppressWildcards MetadataExtractor<*> = extractor
}
