package eu.darken.butler.workspace.core.session

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionDao
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionDatabase
import eu.darken.butler.workspace.core.session.db.WorkspaceUIStateConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceSessionStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceUIStateConverter: WorkspaceUIStateConverter,
    private val dispatcherProvider: DispatcherProvider,
) {

    val database: WorkspaceSessionDatabase by lazy {
        log(TAG) { "Initiliazing database" }
        val db = Room.databaseBuilder(
            context,
            WorkspaceSessionDatabase::class.java,
            "workspace_session.db"
        ).apply {
            addTypeConverter(workspaceUIStateConverter)
        }.build()
        log(TAG) { "Database initialized: $db" }
        db
    }

    val dao: WorkspaceSessionDao
        get() = database.sessionDao()

    fun getWorkspaceCount(sessionId: String): Flow<Int> = dao.getWorkspaceCountFlow(sessionId)

    fun getDatabaseSizeBytes(sessionId: String): Flow<Long> = dao
        .getWorkspaceCountFlow(sessionId)
        .map {
            database.openHelper.writableDatabase.path
                ?.let { File(it) }
                ?.length() ?: 0L

        }.flowOn(dispatcherProvider.IO)

    companion object {
        private val TAG = logTag("Workspace", "Session", "Storage")
        const val DEFAULT_SESSION_ID = "default_session_id"
    }
}