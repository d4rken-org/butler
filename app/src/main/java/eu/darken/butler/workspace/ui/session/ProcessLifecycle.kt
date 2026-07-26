package eu.darken.butler.workspace.ui.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ProcessLifecycle

@Module
@InstallIn(SingletonComponent::class)
object ProcessLifecycleModule {

    @Provides
    @Singleton
    @ProcessLifecycle
    fun processLifecycle(): Lifecycle = ProcessLifecycleOwner.get().lifecycle
}
