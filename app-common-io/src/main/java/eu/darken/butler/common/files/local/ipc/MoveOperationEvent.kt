package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * Events for move operation progress streaming.
 * Sealed interface with Parcelable implementations for IPC.
 */
sealed interface MoveOperationEvent : Parcelable {

    companion object {
        /**
         * Custom CREATOR for sealed interface that reads class name and delegates to subtype CREATOR.
         */
        @JvmField
        val CREATOR: Parcelable.Creator<MoveOperationEvent> = object : Parcelable.Creator<MoveOperationEvent> {
            override fun createFromParcel(parcel: Parcel): MoveOperationEvent {
                // Read class name written by GenericOperationEventStreaming
                val className = parcel.readString() ?: throw IllegalStateException("Class name is null")

                // Delegate to appropriate subtype's CREATOR
                return when (className) {
                    MoveOperationEvent.ScanProgress::class.java.name ->
                        parcel.readParcelable<ScanProgress>(ScanProgress::class.java.classLoader)
                    MoveOperationEvent.MoveProgress::class.java.name ->
                        parcel.readParcelable<MoveProgress>(MoveProgress::class.java.classLoader)
                    MoveOperationEvent.Result::class.java.name ->
                        parcel.readParcelable<Result>(Result::class.java.classLoader)
                    MoveOperationEvent.Error::class.java.name ->
                        parcel.readParcelable<Error>(Error::class.java.classLoader)
                    else -> throw IllegalArgumentException("Unknown MoveOperationEvent type: $className")
                } ?: throw IllegalStateException("Failed to read MoveOperationEvent")
            }

            override fun newArray(size: Int): Array<MoveOperationEvent?> = arrayOfNulls(size)
        }
    }

    /**
     * Emitted during scanning phase when discovering items to move.
     *
     * @param scannedCount Number of items scanned so far
     * @param scannedBytes Total bytes discovered so far
     * @param currentPath Path currently being scanned (with metadata)
     */
    @Parcelize
    data class ScanProgress(
        val scannedCount: Long,
        val scannedBytes: Long,
        val currentPath: LocalPathLookup,
    ) : MoveOperationEvent

    /**
     * Emitted during move phase for each item being moved.
     *
     * @param movedCount Number of items moved so far
     * @param totalCount Total number of items to move (from scan phase)
     * @param movedBytes Bytes moved so far
     * @param totalBytes Total bytes to move (from scan phase)
     * @param currentSource Source path currently being moved (with metadata)
     * @param currentDestination Destination path (nullable during transfer)
     * @param currentFileSize Size of current file being moved
     * @param currentFileBytes Bytes moved of current file
     */
    @Parcelize
    data class MoveProgress(
        val movedCount: Long,
        val totalCount: Long,
        val movedBytes: Long,
        val totalBytes: Long,
        val currentSource: LocalPathLookup,
        val currentDestination: LocalPath?,
        val currentFileSize: Long,
        val currentFileBytes: Long,
    ) : MoveOperationEvent

    /**
     * Final event emitted on successful completion.
     *
     * @param movedItems List of source→destination path pairs (with metadata)
     * @param skippedItems List of skipped paths (user chose to skip, with metadata)
     * @param errorCount Number of errors encountered (issues that were resolved)
     * @param movedBytes Total bytes moved
     */
    @Parcelize
    data class Result(
        val movedItems: List<PathPair>,
        val skippedItems: List<LocalPathLookup>,
        val errorCount: Int,
        val movedBytes: Long,
    ) : MoveOperationEvent

    /**
     * Emitted when operation fails or is cancelled.
     *
     * @param error An `IpcErrorCodec` carrier when the host encoded the failure, a plain message otherwise
     * @param cancelled True if user cancelled, false if error
     */
    @Parcelize
    data class Error(
        val error: String,
        val cancelled: Boolean,
    ) : MoveOperationEvent
}
