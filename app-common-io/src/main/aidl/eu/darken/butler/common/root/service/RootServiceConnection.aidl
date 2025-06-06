package eu.darken.butler.common.root.service;

import eu.darken.butler.common.files.local.ipc.FileOpsConnection;
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsConnection;
import eu.darken.butler.common.shell.ipc.ShellOpsConnection;

interface RootServiceConnection {
    String checkBase();

    FileOpsConnection getFileOps();

    PkgOpsConnection getPkgOps();

    ShellOpsConnection getShellOps();
}