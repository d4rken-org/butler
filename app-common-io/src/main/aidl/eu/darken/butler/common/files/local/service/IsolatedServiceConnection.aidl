package eu.darken.butler.common.files.local.service;

import eu.darken.butler.common.files.local.ipc.FileOpsConnection;

interface IsolatedServiceConnection {
    FileOpsConnection getFileOps();
}
