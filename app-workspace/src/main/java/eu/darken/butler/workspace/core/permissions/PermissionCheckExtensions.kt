package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.first

suspend fun PathPermissionCheck.check(path: APath<*>) = monitor(path).first()