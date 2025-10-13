package eu.darken.butler.common.files.local.ipc;

import eu.darken.butler.common.ipc.RemoteFileHandle;
import eu.darken.butler.common.ipc.RemoteInputStream;
import eu.darken.butler.common.ipc.RemoteOutputStream;
import eu.darken.butler.common.files.LocalPath;
import eu.darken.butler.common.files.local.LocalPathLookup;
import eu.darken.butler.common.files.local.LocalPathLookupExtended;
import eu.darken.butler.common.files.metadata.Ownership;
import eu.darken.butler.common.files.metadata.Permissions;

interface FileOpsConnection {

    RemoteFileHandle file(in LocalPath path, boolean readWrite);

    boolean createDir(in LocalPath path);
    boolean createFile(in LocalPath path);

    boolean canRead(in LocalPath path);
    boolean canWrite(in LocalPath path);

    boolean exists(in LocalPath path);

    boolean delete(in LocalPath path, boolean recursive);

    RemoteInputStream listFilesStream(in LocalPath path);

    LocalPathLookup lookup(in LocalPath path);
    RemoteInputStream lookupFilesStream(in LocalPath path);

    LocalPathLookupExtended lookUpExtended(in LocalPath path);
    List<LocalPathLookupExtended> lookupFilesExtended(in LocalPath path);
    RemoteInputStream lookupFilesExtendedStream(in LocalPath path);

    RemoteInputStream walkStream(in LocalPath path, in List<String> pathDoesNotContain);

    long du(in LocalPath path);

    boolean createSymlink(in LocalPath linkPath, in LocalPath targetPath);

    boolean setModifiedAt(in LocalPath path, in long modifiedAt);

    boolean setPermissions(in LocalPath path, in Permissions permissions);

    boolean setOwnership(in LocalPath path, in Ownership ownership);
}