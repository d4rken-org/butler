package eu.darken.butler.common.files

/**
 * Answer of a strict existence check: present, absent, or "could not tell".
 *
 * The codes are explicit because they cross the AIDL boundary
 * ([eu.darken.butler.common.files.local.ipc.FileOpsConnection.existsStrict]) and must not shift
 * when entries are reordered.
 */
enum class Existence(val ipcCode: Int) {
    PRESENT(1),
    ABSENT(2),
    UNKNOWN(3),
    ;

    companion object {
        fun fromIpcCode(code: Int): Existence = entries.firstOrNull { it.ipcCode == code } ?: UNKNOWN
    }
}
