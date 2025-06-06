package eu.darken.butler.common.files.local.ipc;

import eu.darken.butler.common.ipc.RemoteInputStream;
import eu.darken.butler.common.files.local.LocalPath;

interface DetailedInputSource {
    LocalPath path();
    long length();
    RemoteInputStream input();
}