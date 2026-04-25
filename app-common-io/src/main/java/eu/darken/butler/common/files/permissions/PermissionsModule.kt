package eu.darken.butler.common.files.permissions

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.error.PermissionFixResolver

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionsModule {

    @Binds
    abstract fun bindPermissionFixResolver(
        impl: DefaultPermissionFixResolver,
    ): PermissionFixResolver
}
