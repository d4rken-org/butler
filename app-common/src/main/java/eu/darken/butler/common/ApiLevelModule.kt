package eu.darken.butler.common

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ApiLevelModule {
    @Binds
    abstract fun apiLevelProvider(impl: DefaultApiLevel): ApiLevel
}
