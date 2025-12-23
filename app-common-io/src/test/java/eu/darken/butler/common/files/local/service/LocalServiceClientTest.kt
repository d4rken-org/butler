package eu.darken.butler.common.files.local.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException

/**
 * Unit tests for LocalServiceClient.
 *
 * Note: Full service binding tests require instrumented tests since LocalServiceClient
 * depends on Android service binding (Context.bindService, IBinder, DeathRecipient).
 * These unit tests cover the exception types and data classes.
 */
class LocalServiceClientTest : BaseTest() {

    @Test
    fun `ServiceProcessDiedException is an IOException`() {
        val exception = LocalServiceClient.ServiceProcessDiedException("Process died")

        exception.shouldBeInstanceOf<IOException>()
        exception.message shouldBe "Process died"
        exception.cause shouldBe null
    }

    @Test
    fun `ServiceProcessDiedException preserves cause`() {
        val cause = RuntimeException("Root cause")
        val exception = LocalServiceClient.ServiceProcessDiedException("Process died", cause)

        exception.message shouldBe "Process died"
        exception.cause shouldBe cause
    }

    @Test
    fun `ServiceBindException is an IOException`() {
        val exception = LocalServiceClient.ServiceBindException("Bind failed")

        exception.shouldBeInstanceOf<IOException>()
        exception.message shouldBe "Bind failed"
        exception.cause shouldBe null
    }

    @Test
    fun `ServiceBindException preserves cause`() {
        val cause = RuntimeException("Root cause")
        val exception = LocalServiceClient.ServiceBindException("Bind failed", cause)

        exception.message shouldBe "Bind failed"
        exception.cause shouldBe cause
    }
}
