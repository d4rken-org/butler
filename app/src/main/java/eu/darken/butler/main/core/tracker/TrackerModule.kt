package eu.darken.butler.main.core.tracker

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.workspace.core.tracker.PathAccessTracker

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerModule {

    @Binds
    abstract fun bindPathAccessTracker(impl: PathAccessTrackerImpl): PathAccessTracker
}