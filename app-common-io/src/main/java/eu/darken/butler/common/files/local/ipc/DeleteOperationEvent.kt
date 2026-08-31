package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * Events for delete operation progress streaming.
 * Sealed interface with Parcelable implementations for IPC.
 */
sealed interface DeleteOperationEvent : Parcelable {

    companion object {
        /**
         * Custom CREATOR for sealed interface that reads class name and delegates to subtype CREATOR.
         */
        @JvmField
        val CREATOR: Parcelable.Creator<DeleteOperationEvent> = object : Parcelable.Creator<DeleteOperationEvent> {
            override fun createFromParcel(parcel: Parcel): DeleteOperationEvent {
                // Read class name written by GenericOperationEventStreaming
                val className = parcel.readString() ?: throw IllegalStateException("Class name is null")

                // Delegate to appropriate subtype's CREATOR
                return when (className) {
                    DeleteOperationEvent.ScanProgress::class.java.name ->
                        parcel.readParcelable<ScanProgress>(ScanProgress::class.java.classLoader)
                    DeleteOperationEvent.DeleteProgress::class.java.name ->
                        parcel.readParcelable<DeleteProgress>(DeleteProgress::class.java.classLoader)
                    DeleteOperationEvent.Result::class.java.name ->
                        parcel.readParcelable<Result>(Result::class.java.classLoader)
                    DeleteOperationEvent.Error::class.java.name ->
                        parcel.readParcelable<Error>(Error::class.java.classLoader)
                    else -> throw IllegalArgumentException("Unknown DeleteOperationEvent type: $className")
                } ?: throw IllegalStateException("Failed to read DeleteOperationEvent")
            }

            override fun newArray(size: Int): Array<DeleteOperationEvent?> = arrayOfNulls(size)
        }
    }

    /**
     * Emitted during scanning phase when discovering items to delete.
     *
     * @param scannedCount Number of items scanned so far
     * @param currentPath Path currently being scanned (with metadata)
     */
    @Parcelize
    data class ScanProgress(
        val scannedCount: Long,
        val currentPath: LocalPathLookup,
    ) : DeleteOperationEvent

    /**
     * Emitted during deletion phase for each item deleted.
     *
     * @param deletedCount Number of items deleted so far
     * @param totalCount Total number of items to delete (from scan phase)
     * @param currentPath Path currently being deleted (with metadata)
     * @param currentSize Bytes deleted so far
     * @param totalSize Total bytes to delete (from scan phase)
     */
    @Parcelize
    data class DeleteProgress(
        val deletedCount: Long,
        val totalCount: Long,
        val currentPath: LocalPathLookup,
        val currentSize: Long,
        val totalSize: Long,
    ) : DeleteOperationEvent

    /**
     * Final event emitted on successful completion.
     *
     * @param deletedItems List of successfully deleted paths (with metadata)
     * @param skippedItems List of skipped paths (user chose to skip, with metadata)
     * @param errorCount Number of errors encountered (issues that were resolved)
     */
    @Parcelize
    data class Result(
        val deletedItems: List<LocalPathLookup>,
        val skippedItems: List<LocalPathLookup>,
        val errorCount: Int,
    ) : DeleteOperationEvent

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
    ) : DeleteOperationEvent
}
