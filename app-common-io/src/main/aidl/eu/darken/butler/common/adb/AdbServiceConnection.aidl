package eu.darken.butler.common.adb;

import eu.darken.butler.common.files.local.ipc.FileOpsConnection;
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsConnection;
import eu.darken.butler.common.shell.ipc.ShellOpsConnection;

interface AdbServiceConnection {
    // NEVER move, remove, or change the signature of checkBase(): it must stay the first method of
    // this interface. Its transaction code is what the IpcContract handshake rides on, and the whole
    // point is that it stays valid even when a stale host disagrees about every code below it.
    String checkBase();

    FileOpsConnection getFileOps();

    PkgOpsConnection getPkgOps();

    ShellOpsConnection getShellOps();
}