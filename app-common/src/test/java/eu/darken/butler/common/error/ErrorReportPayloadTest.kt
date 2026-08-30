package eu.darken.butler.common.error

import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class ErrorReportPayloadTest : BaseTest() {

    private val json: Json = SerializationCommonModule().json()

    private fun payload(error: ErrorReportPayload.Error) = ErrorReportPayload(
        incidentId = "abcd1234",
        installId = "11111111-2222-4333-8444-555555555555",
        occurredAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        occurredAtIsApproximate = false,
        packagedAt = Instant.fromEpochMilliseconds(1_700_000_060_000),
        app = ErrorReportPayload.App(version = "v1", flavor = "FOSS", buildType = "DEV"),
        device = ErrorReportPayload.Device(fingerprint = "fp", apiLevel = "34", locale = "en-US"),
        error = error,
    )

    @Test
    fun `the emitted report names its schema version`() {
        val encoded = json.encodeToString(
            ErrorReportPayload.serializer(),
            payload(ErrorReportPayload.Error(className = "java.io.IOException")),
        )
        encoded shouldContain "\"schema\":1"
        json.decodeFromString(ErrorReportPayload.serializer(), encoded).schema shouldBe 1
    }

    @Test
    fun `a report carrying keys this build does not know still decodes`() {
        val fromTheFuture = """
            {
              "schema": 99,
              "incidentId": "abcd1234",
              "installId": "install",
              "occurredAt": "2023-11-14T22:13:20Z",
              "occurredAtIsApproximate": false,
              "packagedAt": "2023-11-14T22:14:20Z",
              "app": {"version": "v1", "flavor": "FOSS", "buildType": "DEV", "channel": "nightly"},
              "device": {"fingerprint": "fp", "apiLevel": "34", "locale": "en-US"},
              "error": {"className": "java.io.IOException"},
              "somethingNew": {"nested": true}
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ErrorReportPayload.serializer(), fromTheFuture)
        decoded.schema shouldBe 99
        decoded.incidentId shouldBe "abcd1234"
    }

    @Test
    fun `the cause chain stops at ten links`() {
        var deepest: Throwable = RuntimeException("root")
        repeat(20) { index -> deepest = RuntimeException("wrapper $index", deepest) }

        deepest.renderCauseChain() shouldHaveSize 10
    }

    @Test
    fun `a throwable that is its own cause terminates the chain`() {
        val selfReferencing = object : RuntimeException("loop") {
            override val cause: Throwable? get() = this
        }

        selfReferencing.renderCauseChain() shouldBe emptyList()
    }

    @Test
    fun `a cycle further down the chain terminates too`() {
        val inner = RuntimeException("inner")
        val outer = RuntimeException("outer", inner)
        inner.initCause(outer)

        outer.renderCauseChain() shouldHaveSize 1
    }

    @Test
    fun `the map id is lifted out of a trace that repeats it on every frame`() {
        val hash = "a".repeat(64)
        val trace = buildString {
            appendLine("java.io.IOException: nope")
            repeat(40) { appendLine("\tat eu.darken.butler.Foo.bar(SourceFile:1) ~[r8-map-id-$hash]") }
        }

        extractMapId(trace) shouldBe hash
    }

    @Test
    fun `a trace without a map id yields none`() {
        extractMapId("java.io.IOException: nope\n\tat eu.darken.butler.Foo.bar(Foo.kt:12)") shouldBe null
    }
}
