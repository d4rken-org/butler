package eu.darken.butler.common.ipc

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Tag every identity frame starts with, so a host predating it can't be mistaken for a stamped one. */
private const val PREFIX = "ipc-host-identity:"
private const val HEADER = "$PREFIX "

/**
 * Wire contract between the app and its privileged hosts (root, and ADB via Shizuku).
 *
 * The host runs out of our own APK but in a separate process, and that process can outlive an
 * in-place app update. The AIDL interfaces it exposes are *non-stable* AIDL, so transaction codes
 * are assigned by declaration order: a host built from a different revision than the client can
 * answer the wrong method for a given code instead of failing. The host also runs the *code* of the
 * APK it was launched from, so a survivor is stale in behaviour, not just in AIDL shape.
 *
 * The signal for that is a [HostIdentity]: the CLIENT reads its own package identity and passes it
 * into the host's launch arguments, the host keeps it for the lifetime of its process and echoes it
 * back as the first line of `checkBase()`. A host launched by an older app echoes that older app's
 * identity, which is the staleness signal. Nothing here is derived from a build-time constant (that
 * would break reproducible builds), and nothing is read by the host itself: the root host's Dagger
 * graph is built on `ActivityThread.getSystemContext()`, which identifies the `android` package
 * rather than ours, so a host-side lookup would mismatch permanently.
 */
object IpcContract {

    /**
     * Identifies the app installation that launched a host process. Compared field by field, so a
     * mismatch can say what actually differed.
     *
     * [lastUpdateTime] is the discriminator that carries the check: it changes on a same-version
     * reinstall, which is exactly the case where the version fields would agree. The others are kept
     * because they make the mismatch log diagnosable.
     */
    data class HostIdentity(
        val versionCode: Long,
        val versionName: String,
        val lastUpdateTime: Long,
        val packageCodePath: String,
    ) {
        /**
         * A single line, field-tagged so a decoder can tell which field disagreed, and escaped so a
         * versionName carrying a delimiter or a newline cannot corrupt the frame or forge fields.
         */
        fun encode(): String = listOf(
            KEY_VERSION_CODE to versionCode.toString(),
            KEY_VERSION_NAME to versionName,
            KEY_LAST_UPDATE_TIME to lastUpdateTime.toString(),
            KEY_PACKAGE_CODE_PATH to packageCodePath,
        ).joinToString(separator = FIELD_SEPARATOR, prefix = "$PREFIX ") { (key, value) ->
            "$key$KEY_VALUE_SEPARATOR${value.escape()}"
        }
    }

    /** Stand-in for a `null` versionName, so the encoding has no optional fields. */
    const val VERSION_NAME_UNKNOWN = "<unknown>"

    /**
     * First line emitted by a host that never received an identity. Decodes to null on purpose. A
     * host predating this handshake emits its own older marker instead, which decodes to null too.
     */
    const val UNSTAMPED = "$PREFIX <unstamped>"

    /** Our own identity. CLIENT-side only, see the object's docs for why the host must not call it. */
    fun current(context: Context): HostIdentity {
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return HostIdentity(
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            versionName = pkgInfo.versionName ?: VERSION_NAME_UNKNOWN,
            lastUpdateTime = pkgInfo.lastUpdateTime,
            packageCodePath = context.packageCodePath,
        )
    }

    /**
     * Parses ONLY the first line of a `checkBase()` reply, everything below it is free-form
     * diagnostics.
     *
     * Returns null for anything that isn't exactly one well-formed frame: a null reply, a host too
     * old to emit one, a bad escape, an unknown/duplicate/missing field, a non-numeric number, or
     * trailing data.
     */
    fun decode(reply: String?): HostIdentity? {
        if (reply == null) return null

        val line = reply.lineSequence().firstOrNull() ?: return null
        if (!line.startsWith(HEADER)) return null

        val fields = mutableMapOf<String, String>()
        line.substring(HEADER.length).split(FIELD_SEPARATOR).forEach { entry ->
            val split = entry.indexOf(KEY_VALUE_SEPARATOR)
            // <= 0 also rejects an empty key, and with it a trailing separator or stray padding
            if (split <= 0) return null
            val key = entry.substring(0, split)
            if (!KEYS.contains(key)) return null
            val value = entry.substring(split + 1).unescape() ?: return null
            if (fields.put(key, value) != null) return null
        }
        if (fields.size != KEYS.size) return null

        return HostIdentity(
            versionCode = fields.getValue(KEY_VERSION_CODE).toLongOrNull() ?: return null,
            versionName = fields.getValue(KEY_VERSION_NAME),
            lastUpdateTime = fields.getValue(KEY_LAST_UPDATE_TIME).toLongOrNull() ?: return null,
            packageCodePath = fields.getValue(KEY_PACKAGE_CODE_PATH),
        )
    }
}

/**
 * The connected host was launched by a different installation of the app than the one talking to it,
 * i.e. it survived an in-place app update. Thrown before any module client is handed out, so no
 * caller can issue a transaction against a host that would misdispatch it or run stale code.
 *
 * The service clients recover from this on the fresh-connection path: the stale host is torn down,
 * its unbind is awaited, and the connection is retried exactly once. A second mismatch (the host is
 * still there, e.g. Shizuku handed back the same running user service) propagates to the caller.
 */
class IpcContractMismatchException(
    message: String,
) : IllegalStateException(message)

private const val ESCAPE = '\\'
private const val FIELD_SEPARATOR = ";"
private const val KEY_VALUE_SEPARATOR = '='
private const val KEY_VERSION_CODE = "versionCode"
private const val KEY_VERSION_NAME = "versionName"
private const val KEY_LAST_UPDATE_TIME = "lastUpdateTime"
private const val KEY_PACKAGE_CODE_PATH = "packageCodePath"
private val KEYS = setOf(KEY_VERSION_CODE, KEY_VERSION_NAME, KEY_LAST_UPDATE_TIME, KEY_PACKAGE_CODE_PATH)

private fun String.escape(): String = buildString(length) {
    this@escape.forEach {
        when (it) {
            ESCAPE -> append("$ESCAPE$ESCAPE")
            ';' -> append("${ESCAPE}s")
            '=' -> append("${ESCAPE}e")
            '\n' -> append("${ESCAPE}n")
            '\r' -> append("${ESCAPE}r")
            else -> append(it)
        }
    }
}

private fun String.unescape(): String? {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val current = this[i]
        if (current != ESCAPE) {
            out.append(current)
            i++
            continue
        }
        if (i + 1 == length) return null
        when (this[i + 1]) {
            ESCAPE -> out.append(ESCAPE)
            's' -> out.append(';')
            'e' -> out.append('=')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            else -> return null
        }
        i += 2
    }
    return out.toString()
}
