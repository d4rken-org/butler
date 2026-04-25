package eu.darken.butler.workspace.core.operations.history

import kotlinx.serialization.Serializable

/**
 * Final outcome of an operation as recorded in [eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity].
 *
 * - [COMPLETED] — finished without errors.
 * - [PARTIAL] — top-level error == null, but the operation reports per-item errors
 *   (e.g., [eu.darken.butler.saver.core.operations.SaveFilesOperation] with mixed permissions).
 * - [FAILED] — top-level error.
 * - [CANCELLED] — `error is CancellationException`.
 */
@Serializable
enum class HistoryOutcome { COMPLETED, PARTIAL, FAILED, CANCELLED }
