package eu.darken.butler.common.files.local.ipc;

import eu.darken.butler.common.ipc.RemoteFileHandle;
import eu.darken.butler.common.ipc.RemoteInputStream;
import eu.darken.butler.common.ipc.RemoteOutputStream;
import eu.darken.butler.common.files.LocalPath;
import eu.darken.butler.common.files.local.LocalPathLookup;
import eu.darken.butler.common.files.local.LocalPathLookupExtended;
import eu.darken.butler.common.files.metadata.Ownership;
import eu.darken.butler.common.files.metadata.Permissions;
import eu.darken.butler.common.files.metadata.FileSystem;
import eu.darken.butler.common.files.local.ipc.FileOperationCallback;

interface FileOpsConnection {

    RemoteFileHandle file(in LocalPath path, boolean readWrite);

    boolean createDir(in LocalPath path, boolean createParents);
    boolean createFile(in LocalPath path, boolean createParents);

    boolean canRead(in LocalPath path);
    boolean canWrite(in LocalPath path);

    boolean exists(in LocalPath path);

    boolean delete(in LocalPath path, boolean recursive);

    RemoteInputStream listFilesStream(in LocalPath path);

    LocalPathLookup lookup(in LocalPath path);
    RemoteInputStream lookupFilesStream(in LocalPath path);

    LocalPathLookupExtended lookupExtended(in LocalPath path);
    List<LocalPathLookupExtended> lookupFilesExtended(in LocalPath path);
    RemoteInputStream lookupFilesExtendedStream(in LocalPath path);

    RemoteInputStream walkStream(in LocalPath path, in List<String> pathDoesNotContain);

    long du(in LocalPath path);

    boolean createSymlink(in LocalPath linkPath, in LocalPath targetPath);

    LocalPath readSymbolicLink(in LocalPath linkPath);

    boolean move(in LocalPath source, in LocalPath destination);

    boolean setModifiedAt(in LocalPath path, in long modifiedAt);

    boolean setPermissions(in LocalPath path, in Permissions permissions);

    boolean setOwnership(in LocalPath path, in Ownership ownership);

    FileSystem getFileSystem(in LocalPath path);

    /**
     * Delete files with progress streaming and interactive issue resolution.
     *
     * @param targets Paths to delete
     * @param recursive If true, recursively delete directories
     * @param ignoreMissing If true, ignore missing files
     * @param callback Callback for resolving issues (null = fail fast)
     * @return RemoteInputStream streaming DeleteOperationEvent instances
     */
    RemoteInputStream deleteStream(
        in List<LocalPath> targets,
        boolean recursive,
        boolean ignoreMissing,
        in FileOperationCallback callback
    );

    /**
     * Copy files with progress streaming and interactive issue resolution.
     *
     * @param sources Paths to copy
     * @param destination Target directory or file path
     * @param overwrite If true, overwrite existing files
     * @param preserveAttributes If true, preserve file attributes (timestamps, permissions)
     * @param followSymlinks If true, follow symlinks to their targets
     * @param callback Callback for resolving issues (null = fail fast)
     * @return RemoteInputStream streaming CopyOperationEvent instances
     */
    RemoteInputStream copyStream(
        in List<LocalPath> sources,
        in LocalPath destination,
        boolean overwrite,
        boolean preserveAttributes,
        boolean followSymlinks,
        in FileOperationCallback callback
    );
}