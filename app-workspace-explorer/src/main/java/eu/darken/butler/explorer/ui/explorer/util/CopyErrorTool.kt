package eu.darken.butler.explorer.ui.explorer.util

import android.content.Context
import android.os.Build
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import javax.inject.Inject

@Reusable
class CopyErrorTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun formatError(operation: ManagedOperation): String? {
        val state = operation.state.value as? Operation.State.Completed
        if (state == null) {
            log(TAG, Logging.Priority.ERROR) { "Operation is not complete: $operation" }
            return null
        }
        val error = state.error
        if (error == null) {
            log(TAG, Logging.Priority.ERROR) { "Operation has no error: $operation" }
            return null
        }
        return """
            # Operation error
            * `${Build.FINGERPRINT}`
            * `${BuildConfigWrap.VERSION_DESCRIPTION}`
            * OperationID: `${operation.id}`
            * Source: ${operation.metadata.origin}
            * CompletedAt: ${state.completedAt}
                      
            ## Description          
            **${operation.metadata.title.get(context)}**
            
            ${operation.metadata.description.get(context)}
            
            ## Error
            ${state.summary.get(context)}
            
            ```java
            ${state.error?.asLog()}
            ```
            
            ## Command
            ```
            ${operation.operation}
            ```
        """.trimIndent()
    }

    companion object {
        private val TAG = logTag("Explorer", "CopyErrorTool")
    }
}