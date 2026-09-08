package eu.darken.butler.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlin.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    fun clock(): Clock = Clock.System
}
