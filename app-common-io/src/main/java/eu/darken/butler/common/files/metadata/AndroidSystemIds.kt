package eu.darken.butler.common.files.metadata

/**
 * Android system UID and GID constants.
 *
 * Based on AOSP platform/system/core/libcutils/include/private/android_filesystem_config.h
 * These are hardcoded system identifiers used by Android (UIDs/GIDs 0-9999).
 *
 * UIDs 10000+ are dynamically assigned to apps and should be resolved via PackageManager.
 * Last updated: 2025-01 from AOSP master branch.
 */
object AndroidSystemIds {

    /**
     * Maps Android system UIDs (0-9999) to their symbolic names.
     */
    val SYSTEM_UIDS = mapOf(
        0 to "root",
        1 to "daemon",
        2 to "bin",
        3 to "sys",
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1008 to "compass",
        1009 to "mount",
        1010 to "wifi",
        1011 to "adb",
        1012 to "install",
        1013 to "media",
        1014 to "dhcp",
        1015 to "sdcard_rw",
        1016 to "vpn",
        1017 to "keystore",
        1018 to "usb",
        1019 to "drm",
        1020 to "mdnsr",
        1021 to "gps",
        1022 to "unused1",
        1023 to "media_rw",
        1024 to "mtp",
        1025 to "unused2",
        1026 to "drmrpc",
        1027 to "nfc",
        1028 to "sdcard_r",
        1029 to "clat",
        1030 to "loop_radio",
        1031 to "media_drm",
        1032 to "package_info",
        1033 to "sdcard_pics",
        1034 to "sdcard_av",
        1035 to "sdcard_all",
        1036 to "logd",
        1037 to "shared_relro",
        1038 to "dbus",
        1039 to "tlsdate",
        1040 to "media_ex",
        1041 to "audioserver",
        1042 to "metrics_coll",
        1043 to "metricsd",
        1044 to "webserv",
        1045 to "debuggerd",
        1046 to "media_codec",
        1047 to "cameraserver",
        1048 to "firewall",
        1049 to "trunks",
        1050 to "nvram",
        1051 to "dns",
        1052 to "dns_tether",
        1053 to "webview_zygote",
        1054 to "vehicle_network",
        1055 to "media_audio",
        1056 to "media_video",
        1057 to "media_image",
        1058 to "tombstoned",
        1059 to "media_obb",
        1060 to "ese",
        1061 to "ota_update",
        1062 to "automotive_evs",
        1063 to "lowpan",
        1064 to "hsm",
        1065 to "reserved_disk",
        1066 to "statsd",
        1067 to "incidentd",
        1068 to "secure_element",
        1069 to "lmkd",
        1070 to "llkd",
        1071 to "iorapd",
        1072 to "gpu_service",
        1073 to "network_stack",
        1074 to "gsid",
        1075 to "fsverity_cert",
        1076 to "credstore",
        1077 to "external_storage",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        1080 to "context_hub",
        1081 to "virtualizationservice",
        1082 to "artd",
        1083 to "uwb",
        1084 to "thread_network",
        1085 to "diced",
        1086 to "dmesgd",
        1087 to "jc_weaver",
        1088 to "jc_strongbox",
        1089 to "jc_identitycred",
        1090 to "sdk_sandbox",
        1091 to "security_log_writer",
        1092 to "prng_seeder",
        1093 to "uprobestats",
        1094 to "cros_ec",
        1095 to "mmd",
        2000 to "shell",
        2001 to "cache",
        2002 to "diag",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        3008 to "readproc",
        3009 to "wakelock",
        3010 to "uhid",
        3011 to "sensors",
        3012 to "rfs",
        3013 to "ddr",
        5000 to "oem_reserved_start",
        9997 to "everybody",
        9998 to "misc",
        9999 to "nobody",
    )

    /**
     * Maps Android system GIDs (0-9999) to their symbolic names.
     * Many GIDs match UIDs, but some are permission-specific groups.
     */
    val SYSTEM_GIDS = mapOf(
        0 to "root",
        1 to "daemon",
        2 to "bin",
        3 to "sys",
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1008 to "compass",
        1009 to "mount",
        1010 to "wifi",
        1011 to "adb",
        1012 to "install",
        1013 to "media",
        1014 to "dhcp",
        1015 to "sdcard_rw",
        1016 to "vpn",
        1017 to "keystore",
        1018 to "usb",
        1019 to "drm",
        1020 to "mdnsr",
        1021 to "gps",
        1022 to "unused1",
        1023 to "media_rw",
        1024 to "mtp",
        1025 to "unused2",
        1026 to "drmrpc",
        1027 to "nfc",
        1028 to "sdcard_r",
        1029 to "clat",
        1030 to "loop_radio",
        1031 to "media_drm",
        1032 to "package_info",
        1033 to "sdcard_pics",
        1034 to "sdcard_av",
        1035 to "sdcard_all",
        1036 to "logd",
        1037 to "shared_relro",
        1038 to "dbus",
        1039 to "tlsdate",
        1040 to "media_ex",
        1041 to "audioserver",
        1042 to "metrics_coll",
        1043 to "metricsd",
        1044 to "webserv",
        1045 to "debuggerd",
        1046 to "media_codec",
        1047 to "cameraserver",
        1048 to "firewall",
        1049 to "trunks",
        1050 to "nvram",
        1051 to "dns",
        1052 to "dns_tether",
        1053 to "webview_zygote",
        1054 to "vehicle_network",
        1055 to "media_audio",
        1056 to "media_video",
        1057 to "media_image",
        1058 to "tombstoned",
        1059 to "media_obb",
        1060 to "ese",
        1061 to "ota_update",
        1062 to "automotive_evs",
        1063 to "lowpan",
        1064 to "hsm",
        1065 to "reserved_disk",
        1066 to "statsd",
        1067 to "incidentd",
        1068 to "secure_element",
        1069 to "lmkd",
        1070 to "llkd",
        1071 to "iorapd",
        1072 to "gpu_service",
        1073 to "network_stack",
        1074 to "gsid",
        1075 to "fsverity_cert",
        1076 to "credstore",
        1077 to "external_storage",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        1080 to "context_hub",
        1081 to "virtualizationservice",
        1082 to "artd",
        1083 to "uwb",
        1084 to "thread_network",
        1085 to "diced",
        1086 to "dmesgd",
        1087 to "jc_weaver",
        1088 to "jc_strongbox",
        1089 to "jc_identitycred",
        1090 to "sdk_sandbox",
        1091 to "security_log_writer",
        1092 to "prng_seeder",
        1093 to "uprobestats",
        1094 to "cros_ec",
        1095 to "mmd",
        2000 to "shell",
        2001 to "cache",
        2002 to "diag",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        3008 to "readproc",
        3009 to "wakelock",
        3010 to "uhid",
        3011 to "sensors",
        3012 to "rfs",
        3013 to "ddr",
        5000 to "oem_reserved_start",
        9997 to "everybody",
        9998 to "misc",
        9999 to "nobody",
    )

    /**
     * The starting UID for application packages.
     * UIDs >= this value are dynamically assigned to apps.
     */
    const val AID_APP_START = 10000

    /**
     * The ending UID for application packages (before isolated processes).
     */
    const val AID_APP_END = 19999

    /**
     * The starting UID for isolated processes.
     */
    const val AID_ISOLATED_START = 99000

    /**
     * The ending UID for isolated processes.
     */
    const val AID_ISOLATED_END = 99999

    /**
     * The starting UID for SDK sandbox processes.
     */
    const val AID_SDK_SANDBOX_PROCESS_START = 20000

    /**
     * The ending UID for SDK sandbox processes.
     */
    const val AID_SDK_SANDBOX_PROCESS_END = 29999

    /**
     * Checks if a UID is a system UID (0-9999).
     */
    fun isSystemUid(uid: Int): Boolean = uid in 0..9999

    /**
     * Checks if a UID is an app UID (10000-19999).
     */
    fun isAppUid(uid: Int): Boolean = uid in AID_APP_START..AID_APP_END

    /**
     * Checks if a UID is an isolated process UID (99000-99999).
     */
    fun isIsolatedUid(uid: Int): Boolean = uid in AID_ISOLATED_START..AID_ISOLATED_END

    /**
     * Checks if a UID is an SDK sandbox process UID (20000-29999).
     */
    fun isSdkSandboxUid(uid: Int): Boolean = uid in AID_SDK_SANDBOX_PROCESS_START..AID_SDK_SANDBOX_PROCESS_END
}
