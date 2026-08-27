package eu.darken.butler.common.serialization

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.smb.SmbPathLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import javax.inject.Qualifier
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class SerializationIOModule {

    @Provides
    @Singleton
    @SerializationIO
    fun json(
        @SerializationCommon jsonCommon: Json = SerializationCommonModule().json()
    ): Json = Json(jsonCommon) {
        serializersModule = SerializersModule {
            include(jsonCommon.serializersModule)
            polymorphic(APath::class) {
                subclass(LocalPath::class)
                subclass(SAFPath::class)
                subclass(ArchivePath::class)
                subclass(SmbPath::class)
            }
            polymorphic(APathLookup::class) {
                subclass(LocalPathLookup::class)
                subclass(SAFPathLookup::class)
                subclass(ArchivePathLookup::class)
                subclass(SmbPathLookup::class)
            }
        }
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class SerializationIO
