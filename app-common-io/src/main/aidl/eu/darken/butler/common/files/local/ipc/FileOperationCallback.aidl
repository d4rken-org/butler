package eu.darken.butler.common.files.local.ipc;

import eu.darken.butler.common.files.local.ipc.FileOperationIssue;
import eu.darken.butler.common.files.local.ipc.FileOperationIssueResolution;

/**
 * Generic callback for all file operation issue resolution.
 * Used by Delete, Copy, Move, and future operations.
 *
 * This callback is invoked by the host process (root/ADB) when an issue occurs
 * during file operations. The host blocks until the client returns a resolution.
 */
interface FileOperationCallback {
    /**
     * Called when an issue occurs during file operation.
     *
     * @param issue The issue that occurred (permission denied, file exists, etc.)
     * @return Resolution chosen by user (skip, retry, overwrite, cancel, etc.)
     */
    FileOperationIssueResolution onIssue(in FileOperationIssue issue);
}
