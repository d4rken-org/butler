package eu.darken.butler.workspace.core.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkspaceSessionModule {

    private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "workspace_session"
    )

    @Provides
    @Singleton
    @Named("workspace_session")
    fun provideSessionDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.sessionDataStore
}