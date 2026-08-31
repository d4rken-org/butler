package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * Events for copy operation progress streaming.
 * Sealed interface with Parcelable implementations for IPC.
 */
sealed interface CopyOperationEvent : Parcelable {

    companion object {
        /**
         * Custom CREATOR for sealed interface that reads class name and delegates to subtype CREATOR.
         */
        @JvmField
        val CREATOR: Parcelable.Creator<CopyOperationEvent> = object : Parcelable.Creator<CopyOperationEvent> {
            override fun createFromParcel(parcel: Parcel): CopyOperationEvent {
                // Read class name written by GenericOperationEventStreaming
                val className = parcel.readString() ?: throw IllegalStateException("Class name is null")

                // Delegate to appropriate subtype's CREATOR
                return when (className) {
                    CopyOperationEvent.ScanProgress::class.java.name ->
                        parcel.readParcelable<ScanProgress>(ScanProgress::class.java.classLoader)
                    CopyOperationEvent.CopyProgress::class.java.name ->
                        parcel.readParcelable<CopyProgress>(CopyProgress::class.java.classLoader)
                    CopyOperationEvent.Result::class.java.name ->
                        parcel.readParcelable<Result>(Result::class.java.classLoader)
                    CopyOperationEvent.Error::class.java.name ->
                        parcel.readParcelable<Error>(Error::class.java.classLoader)
                    else -> throw IllegalArgumentException("Unknown CopyOperationEvent type: $className")
                } ?: throw IllegalStateException("Failed to read CopyOperationEvent")
            }

            override fun newArray(size: Int): Array<CopyOperationEvent?> = arrayOfNulls(size)
        }
    }

    /**
     * Emitted during scanning phase when discovering items to copy.
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
    ) : CopyOperationEvent

    /**
     * Emitted during copy phase for each item being copied.
     *
     * @param copiedCount Number of items copied so far
     * @param totalCount Total number of items to copy (from scan phase)
     * @param copiedBytes Bytes copied so far
     * @param totalBytes Total bytes to copy (from scan phase)
     * @param currentSource Source path currently being copied (with metadata)
     * @param currentDestination Destination path (nullable during transfer)
     * @param currentFileSize Size of current file being copied
     * @param currentFileBytes Bytes copied of current file
     */
    @Parcelize
    data class CopyProgress(
        val copiedCount: Long,
        val totalCount: Long,
        val copiedBytes: Long,
        val totalBytes: Long,
        val currentSource: LocalPathLookup,
        val currentDestination: LocalPath?,
        val currentFileSize: Long,
        val currentFileBytes: Long,
    ) : CopyOperationEvent

    /**
     * Final event emitted on successful completion.
     *
     * @param copiedItems List of source→destination path pairs (with metadata)
     * @param skippedItems List of skipped paths (user chose to skip, with metadata)
     * @param errorCount Number of errors encountered (issues that were resolved)
     * @param copiedBytes Total bytes copied
     */
    @Parcelize
    data class Result(
        val copiedItems: List<PathPair>,
        val skippedItems: List<LocalPathLookup>,
        val errorCount: Int,
        val copiedBytes: Long,
    ) : CopyOperationEvent

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
    ) : CopyOperationEvent
}
