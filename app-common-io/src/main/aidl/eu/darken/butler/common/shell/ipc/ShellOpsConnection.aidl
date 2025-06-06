package eu.darken.butler.common.shell.ipc;

import eu.darken.butler.common.shell.ipc.ShellOpsCmd;
import eu.darken.butler.common.shell.ipc.ShellOpsResult;

interface ShellOpsConnection {

   ShellOpsResult execute(in ShellOpsCmd cmd);

}