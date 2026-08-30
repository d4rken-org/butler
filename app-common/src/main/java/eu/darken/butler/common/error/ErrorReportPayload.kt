package eu.darken.butler.common.error

import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The machine-readable half of a shared error report (`report.json`), next to the raw
 * `stacktrace.txt` and `report.log` in the same zip.
 *
 * [context] is a flat string map on purpose: sites add keys freely, and a later move to typed
 * fields bumps [schema] instead of breaking readers of the old shape.
 */
@Serializable
data class ErrorReportPayload(
    val schema: Int = SCHEMA,
    val incidentId: String,
    val installId: String,
    @Serializable(with = InstantSerializer::class) val occurredAt: Instant,
    val occurredAtIsApproximate: Boolean,
    @Serializable(with = InstantSerializer::class) val packagedAt: Instant,
    val summary: String? = null,
    val app: App,
    val device: Device,
    val error: Error,
    val context: Map<String, String> = emptyMap(),
) {

    @Serializable
    data class App(
        val version: String,
        val flavor: String,
        val buildType: String,
        /** The R8 mapping id, lifted out of the trace where it repeats on every frame. */
        val mapId: String? = null,
    )

    @Serializable
    data class Device(
        val fingerprint: String,
        val apiLevel: String,
        val locale: String,
    )

    @Serializable
    data class Error(
        val className: String,
        val message: String? = null,
        val causeChain: List<String> = emptyList(),
    )

    companion object {
        const val SCHEMA = 1
    }
}

/**
 * `[cause]: [cause.cause]: …`, one rendered entry per link. Bounded by [MAX_CAUSE_DEPTH] and an
 * identity guard, so a throwable whose cause is itself (or a cycle further down) terminates.
 */
fun Throwable.renderCauseChain(): List<String> {
    val rendered = mutableListOf<String>()
    val seen = mutableListOf<Throwable>(this)
    var current = cause
    while (current != null && rendered.size < MAX_CAUSE_DEPTH) {
        if (seen.any { it === current }) break
        seen.add(current)
        rendered.add("${current.javaClass.name}: ${current.message}")
        current = current.cause
    }
    return rendered
}

/** The first R8 mapping id in [stackTrace], which repeats it once per frame. */
fun extractMapId(stackTrace: String): String? = MAP_ID_PATTERN.find(stackTrace)?.groupValues?.get(1)

private const val MAX_CAUSE_DEPTH = 10
private val MAP_ID_PATTERN = Regex("r8-map-id-([0-9a-f]{64})")
