package eu.darken.butler.common.pkgs.pkgops.ipc;

import android.content.pm.PackageInfo;
import eu.darken.butler.common.ipc.RemoteInputStream;
import eu.darken.butler.common.pkgs.features.InstallId;
import eu.darken.butler.common.pkgs.pkgops.ipc.RunningPackagesResult;

interface PkgOpsConnection {

    String getUserNameForUID(int uid);

    String getGroupNameforGID(int gid);

    RunningPackagesResult getRunningPackages();

    boolean forceStop(String packageName);

    List<PackageInfo> getInstalledPackagesAsUser(long flags, int handleId);

    RemoteInputStream getInstalledPackagesAsUserStream(long flags, int handleId);

    void setApplicationEnabledSetting (String packageName, int newState, int flags);

    boolean grantPermission(String packageName, int handleId, String permissionId);

    boolean revokePermission(String packageName, int handleId, String permissionId);

    boolean setAppOps(String packageName, int handleId, String key, String value);

    boolean uninstallPackage(String packageName, int handleId);

    boolean clearData(String packageName, int handleId);

    // Appended, never inserted, and never removed mid-interface: this is non-stable AIDL, so
    // transaction codes come from declaration order. Adding or deleting a method mid-interface
    // renumbers every later one, and a root/Shizuku host that survived an in-place app update would
    // then dispatch the wrong transaction. Any such change must bump IpcContract.VERSION so the
    // handshake in checkBase() rejects a mismatched host instead of misdispatching.
    void setComponentEnabledSetting(String packageName, String className, int newState, int flags);
}