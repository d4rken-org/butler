package eu.darken.butler.main.core.motd

import eu.darken.butler.common.http.HttpModule
import eu.darken.butler.common.serialization.SerializationAppModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.util.Locale

class MotdDataTest : BaseTest() {

    private lateinit var webServer: MockWebServer
    private lateinit var motdEndpoint: MotdEndpoint
    private val mockUrl: String
        get() = webServer.url("/").toString()

    @BeforeEach
    fun setup() {
        webServer = MockWebServer()
        motdEndpoint = MotdEndpoint(
            dispatcherProvider = TestDispatcherProvider(),
            baseHttpClient = HttpModule().baseHttpClient(),
            baseJson = SerializationAppModule().json(),
        )
        motdEndpoint.endpointUrlOverride = mockUrl

        File(".").listFiles()
    }

    @AfterEach
    fun teardown() {
        webServer.close()
    }

    private fun mockListingResponse(flavor: String, type: String, locale: Locale) {
        val response = """
            [
                {
                    "name": "motd-${locale.language}.json",
                    "download_url": "${mockUrl}d4rken-org/sdmaid-se/main/motd/$flavor/$type/motd-${locale.language}.json",
                    "type": "file"
                }
            ]
        """.trimIndent()
        webServer.enqueue(MockResponse().setBody(response))
    }

    private suspend fun checkMotds(
        flavor: String,
        type: String,
    ) {
        File("../motd/$flavor/$type").listFiles()?.forEach { motdFile ->
            mockListingResponse(flavor, type, Locale.ENGLISH)
            webServer.enqueue(MockResponse().setBody(motdFile.readText()))
            motdEndpoint.getMotd(Locale.ENGLISH)!!.apply {
                allowTranslation shouldBe false
                motd.primaryLink?.startsWith("https://")
            }

            val missingLocale = Locale.forLanguageTag("aa-aa")
            mockListingResponse(flavor, type, missingLocale)
            webServer.enqueue(MockResponse().setBody(motdFile.readText()))
            motdEndpoint.getMotd(missingLocale)!!.allowTranslation shouldBe true
        }
    }

    @Test
    fun `foss dev`() = runTest {
        withClue("There should always be a test file for dev") {
            File("../motd/foss/dev").list().isNullOrEmpty() shouldBe false
        }
        checkMotds("foss", "dev")
    }

    @Test
    fun `foss beta`() = runTest {
        checkMotds("foss", "beta")
    }

    @Test
    fun `foss release`() = runTest {
        checkMotds("foss", "release")
    }

    @Test
    fun `gplay dev`() = runTest {
        withClue("There should always be a test file for dev") {
            File("../motd/gplay/dev").list().isNullOrEmpty() shouldBe false
        }
        checkMotds("gplay", "dev")
    }

    @Test
    fun `gplay beta`() = runTest {
        checkMotds("gplay", "beta")
    }

    @Test
    fun `gplay release`() = runTest {
        checkMotds("gplay", "release")
    }

    @Test
    fun `errors are rethrown`() = runTest {
        webServer.enqueue(MockResponse().setResponseCode(500))
        shouldThrow<Exception> {
            motdEndpoint.getMotd(Locale.ENGLISH)
        }
    }

    @Test
    fun `404 returns null`() = runTest {
        webServer.enqueue(MockResponse().setResponseCode(404))
        motdEndpoint.getMotd(Locale.ENGLISH) shouldBe null
    }

    @Test
    fun `optional title and description are parsed`() = runTest {
        mockListingResponse("foss", "dev", Locale.ENGLISH)
        webServer.enqueue(
            MockResponse().setBody(
                """
                {
                    "id": "a1b2c3d4-0000-4a1b-9c2d-deadbeef0001",
                    "title": "A title",
                    "message": "A message",
                    "description": "A description",
                    "primaryLink": "https://example.com"
                }
                """.trimIndent()
            )
        )
        motdEndpoint.getMotd(Locale.ENGLISH)!!.motd.apply {
            title shouldBe "A title"
            message shouldBe "A message"
            description shouldBe "A description"
        }
    }

    @Test
    fun `optional fields default to null when absent`() = runTest {
        mockListingResponse("foss", "dev", Locale.ENGLISH)
        webServer.enqueue(
            MockResponse().setBody(
                """
                {
                    "id": "a1b2c3d4-0000-4a1b-9c2d-deadbeef0001",
                    "message": "Only a message"
                }
                """.trimIndent()
            )
        )
        motdEndpoint.getMotd(Locale.ENGLISH)!!.motd.apply {
            title shouldBe null
            description shouldBe null
            primaryLink shouldBe null
            minimumVersion shouldBe null
            maximumVersion shouldBe null
        }
    }

    @Test
    fun `falls back deterministically when locale and english are missing`() = runTest {
        val listing = """
            [
                {"name": "motd-zz.json", "download_url": "${mockUrl}zz.json", "type": "file"},
                {"name": "motd-aa.json", "download_url": "${mockUrl}aa.json", "type": "file"}
            ]
        """.trimIndent()
        webServer.enqueue(MockResponse().setBody(listing))
        webServer.enqueue(
            MockResponse().setBody(
                """{"id":"a1b2c3d4-0000-4a1b-9c2d-deadbeef0001","message":"fallback"}"""
            )
        )
        // Neither the requested locale nor "-en" is present, and there are multiple json
        // files: the old singleOrNull returned null; the fallback now picks one by name.
        motdEndpoint.getMotd(Locale.forLanguageTag("xx"))!!.motd.message shouldBe "fallback"
    }
}