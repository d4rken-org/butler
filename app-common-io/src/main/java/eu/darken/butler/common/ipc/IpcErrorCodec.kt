package eu.darken.butler.common.ipc

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.UnknownFileTypeException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What a host-side failure looks like on the wire.
 *
 * [className] is the host-side type, [rawMessage] the message as it was passed to that type's
 * constructor. [extras] carries whatever a code needs beyond path and message to be rebuilt.
 */
@Serializable
data class IpcErrorPayload(
    val code: IpcErrorCode,
    val className: String,
    val rawMessage: String? = null,
    val rawPath: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val causeChain: List<String> = emptyList(),
    val hostStack: List<IpcStackFrame> = emptyList(),
)

/**
 * A stack frame as four fields rather than a rendered line: rendering is lossy in several
 * directions ("Native Method", "Unknown Source", module and classloader prefixes) and a parser for
 * it would fabricate frame data on the forms it doesn't know.
 */
@Serializable
data class IpcStackFrame(
    val className: String,
    val methodName: String,
    val fileName: String? = null,
    val lineNumber: Int = -1,
)

/**
 * Carries a host-side exception through a synchronous binder call.
 *
 * A `Parcel` transports only an exception's message, and only for the handful of types
 * `Parcel.writeException` knows about, so everything the app needs (type, path, causes, host stack)
 * is packed into that message as JSON behind [MARKER].
 */
object IpcErrorCodec {

    /**
     * Prefix that identifies a carrier. The decoder keys on it alone, so an
     * `UnsupportedOperationException` the host threw for real still arrives as itself.
     */
    const val MARKER = "#BTLR_IPC_ERROR_V1#"

    fun encode(error: Throwable): String {
        val encoded = runCatching { MARKER + json.encodeToString(SERIALIZER, error.toPayload()) }
            .onFailure { log(TAG, WARN) { "Failed to encode $error: ${it.asLog()}" } }
            .getOrNull()

        // The encoder runs while the host is already handling a failure: a payload that would blow
        // the transaction buffer must degrade, not add a second failure on top of the first.
        if (encoded != null && encoded.toByteArray().size <= MAX_PAYLOAD_BYTES) return encoded

        val minimal = runCatching { MARKER + json.encodeToString(SERIALIZER, error.toMinimalPayload()) }.getOrNull()
        if (minimal != null && minimal.toByteArray().size <= MAX_PAYLOAD_BYTES) return minimal

        return MARKER + UNENCODABLE
    }

    fun decode(carrierMessage: String, localStack: Array<StackTraceElement>): Throwable {
        val raw = carrierMessage.removePrefix(MARKER)

        val body = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (body == null) {
            log(TAG, WARN) { "Undecodable IPC error payload: ${raw.truncate()}" }
            return UnwrappedIPCException("$UNDECODABLE${raw.truncate()}")
        }

        val payload = runCatching { json.decodeFromJsonElement(SERIALIZER, body) }.getOrNull()
        if (payload == null) {
            log(TAG, WARN) { "Unusable IPC error payload, salvaging diagnostics: ${raw.truncate()}" }
            return runCatching { body.salvage(localStack) }.getOrNull()
                ?: UnwrappedIPCException("$UNDECODABLE${raw.truncate()}")
        }

        // A payload that decoded is still a payload some other build wrote: its own bounds are not
        // this decoder's bounds, so everything that gets retained or rendered is capped again here.
        val cause = payload.causeChain.take(MAX_CAUSE_DEPTH).map { it.truncate() }.synthesizeCause()
        val rebuilt = runCatching { payload.rebuild(cause) }.getOrNull() ?: run {
            log(TAG, WARN) { "Can't rebuild ${payload.className} as ${payload.code}, unwrapping instead" }
            payload.asUnwrapped(cause)
        }

        rebuilt.attachHostStack(payload.hostStack.take(MAX_STACK_FRAMES).map { it.truncate() }, localStack)
        return rebuilt
    }

    private fun Throwable.toPayload() = IpcErrorPayload(
        code = classify(),
        className = javaClass.name.truncate(),
        rawMessage = rawMessage()?.truncate(),
        rawPath = (this as? PathException)?.path?.path?.truncate(),
        extras = extras(),
        causeChain = boundedCauses().map { it.toString().truncate() },
        hostStack = stackTrace.take(MAX_STACK_FRAMES).map {
            IpcStackFrame(
                className = it.className.truncate(),
                methodName = it.methodName.truncate(),
                fileName = it.fileName?.truncate(),
                lineNumber = it.lineNumber,
            )
        },
    )

    private fun Throwable.toMinimalPayload() = IpcErrorPayload(
        code = IpcErrorCode.UNMAPPED,
        className = javaClass.name.truncate(),
        rawMessage = message?.truncate(),
    )

    /** Most derived first, the subtypes carry reconstruction data their parents don't have. */
    private fun Throwable.classify(): IpcErrorCode = when (this) {
        is PathAlreadyExistsException -> IpcErrorCode.PATH_ALREADY_EXISTS
        is PathPermissionDeniedException -> IpcErrorCode.PATH_PERMISSION_DENIED
        is UnknownFileTypeException -> IpcErrorCode.PATH_UNKNOWN_FILE_TYPE
        is ReadException -> IpcErrorCode.PATH_READ
        is WriteException -> IpcErrorCode.PATH_WRITE
        is SecurityException -> IpcErrorCode.SECURITY
        is IllegalArgumentException -> IpcErrorCode.ILLEGAL_ARGUMENT
        is IOException -> IpcErrorCode.IO
        else -> IpcErrorCode.UNMAPPED
    }

    /**
     * [PathException] appends ` <-> path` to whatever message it was constructed with. The decoder
     * hands the message back to that same constructor, so the suffix must not travel with it.
     */
    private fun Throwable.rawMessage(): String? {
        val path = (this as? PathException)?.path ?: return message
        return message?.removeSuffix(" <-> ${path.path}")
    }

    private fun Throwable.extras(): Map<String, String> {
        val extras = when (this) {
            is PathPermissionDeniedException -> mapOf(KEY_REASON to reason.name, KEY_OPERATION to operation)
            is UnknownFileTypeException -> mapOf(KEY_FILE_TYPE to lookup.fileType.name)
            else -> emptyMap()
        }
        return extras.entries.associate { it.key.truncate() to it.value.truncate() }
    }

    /**
     * `Throwable.causeChain` walks `cause` without cycle detection, which a failing host cannot
     * afford, so the walk is bounded by identity and depth here.
     */
    private fun Throwable.boundedCauses(): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        seen.add(this)
        val causes = mutableListOf<Throwable>()
        var current = cause
        while (current != null && causes.size < MAX_CAUSE_DEPTH && seen.add(current)) {
            causes.add(current)
            current = current.cause
        }
        return causes
    }

    /**
     * Rebuilt as plain [IOException]s because only the text matters: `PermissionErrorClassifier`
     * reads EROFS/EPERM out of the messages along the cause chain, and the path exceptions render
     * their cause into the error the user sees.
     */
    private fun List<String>.synthesizeCause(): Throwable? {
        var cause: Throwable? = null
        asReversed().forEach { cause = IOException(it, cause) }
        return cause
    }

    /** Null when the payload lacks something the code needs, e.g. a [PathException] without a path. */
    private fun IpcErrorPayload.rebuild(cause: Throwable?): Throwable? = when (code) {
        IpcErrorCode.PATH_READ -> ReadException(rawMessage, localPath() ?: return null, cause)
        IpcErrorCode.PATH_WRITE -> WriteException(rawMessage, localPath() ?: return null, cause)
        IpcErrorCode.PATH_ALREADY_EXISTS -> PathAlreadyExistsException(rawMessage, localPath() ?: return null, cause)
        IpcErrorCode.PATH_PERMISSION_DENIED -> PathPermissionDeniedException(
            path = localPath() ?: return null,
            operation = extras[KEY_OPERATION] ?: return null,
            reason = PathPermissionDeniedException.Reason.entries
                .firstOrNull { it.name == extras[KEY_REASON] } ?: return null,
            cause = cause,
        )
        IpcErrorCode.PATH_UNKNOWN_FILE_TYPE -> UnknownFileTypeException(
            lookup = LocalPathLookup(
                lookedUp = localPath() ?: return null,
                fileType = FileType.entries.firstOrNull { it.name == extras[KEY_FILE_TYPE] } ?: return null,
                size = null,
                modifiedAt = null,
            ),
            cause = cause,
        )
        IpcErrorCode.IO -> IOException(rawMessage, cause)
        IpcErrorCode.SECURITY -> SecurityException(rawMessage, cause)
        IpcErrorCode.ILLEGAL_ARGUMENT -> IllegalArgumentException(rawMessage, cause)
        IpcErrorCode.UNMAPPED -> asUnwrapped(cause)
    }

    private fun IpcErrorPayload.localPath(): LocalPath? = rawPath?.let { LocalPath.build(it) }

    private fun IpcErrorPayload.asUnwrapped(cause: Throwable?) = UnwrappedIPCException(
        message = rawMessage?.let { "$className: $it" } ?: className,
        cause = cause,
    )

    /**
     * A body that isn't a valid [IpcErrorPayload] can still carry usable diagnostics, e.g. when the
     * required `code` didn't survive. Every field is read on its own, so one malformed entry costs
     * only itself.
     */
    private fun JsonObject.salvage(localStack: Array<StackTraceElement>): Throwable {
        val className = string(FIELD_CLASS_NAME)
        val rawMessage = string(FIELD_RAW_MESSAGE)
        val message = when {
            className == null -> "$UNDECODABLE${rawMessage ?: toString()}".truncate()
            rawMessage == null -> className
            else -> "$className: $rawMessage"
        }

        val salvaged = UnwrappedIPCException(
            message = message,
            cause = strings(FIELD_CAUSE_CHAIN).synthesizeCause(),
        )
        salvaged.attachHostStack(frames(FIELD_HOST_STACK), localStack)
        return salvaged
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.truncate()

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)
        ?.take(MAX_CAUSE_DEPTH)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.truncate() }
        ?: emptyList()

    private fun JsonObject.frames(key: String): List<IpcStackFrame> = (this[key] as? JsonArray)
        ?.take(MAX_STACK_FRAMES)
        ?.mapNotNull { runCatching { json.decodeFromJsonElement(FRAME_SERIALIZER, it) }.getOrNull() }
        ?: emptyList()

    private fun Throwable.attachHostStack(hostStack: List<IpcStackFrame>, localStack: Array<StackTraceElement>) {
        if (hostStack.isEmpty()) return
        val remote = hostStack.map { StackTraceElement(it.className, it.methodName, it.fileName, it.lineNumber) }
        stackTrace = (remote + localStack)
            .filterNot {
                it.className.startsWith("android.os.Binder") || it.className.startsWith("android.os.Parcel")
            }
            .toTypedArray()
    }

    private fun String.truncate(): String = if (length <= MAX_STRING_LENGTH) this else take(MAX_STRING_LENGTH)

    private fun IpcStackFrame.truncate() = IpcStackFrame(
        className = className.truncate(),
        methodName = methodName.truncate(),
        fileName = fileName?.truncate(),
        lineNumber = lineNumber,
    )

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
    private val SERIALIZER = IpcErrorPayload.serializer()
    private val FRAME_SERIALIZER = IpcStackFrame.serializer()

    private val TAG = logTag("IPC", "ErrorCodec")
    private const val UNDECODABLE = "Undecodable IPC error payload: "

    /** Last resort when even the minimal payload doesn't fit: constant, valid, and within bounds. */
    private const val UNENCODABLE = """{"code":"UNMAPPED","className":"eu.darken.butler.common.ipc.""" +
        """IpcErrorCodec","rawMessage":"Host error too large to encode"}"""

    private const val FIELD_CLASS_NAME = "className"
    private const val FIELD_RAW_MESSAGE = "rawMessage"
    private const val FIELD_CAUSE_CHAIN = "causeChain"
    private const val FIELD_HOST_STACK = "hostStack"
    private const val KEY_REASON = "reason"
    private const val KEY_OPERATION = "operation"
    private const val KEY_FILE_TYPE = "fileType"
    private const val MAX_CAUSE_DEPTH = 10
    private const val MAX_STACK_FRAMES = 100
    private const val MAX_STRING_LENGTH = 4000
    private const val MAX_PAYLOAD_BYTES = 64 * 1024
}
