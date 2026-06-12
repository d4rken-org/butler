package eu.darken.butler.workspace.core

import dagger.MapKey

@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkspaceTypeKey(val value: Workspace.Type)
