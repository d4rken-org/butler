package eu.darken.butler.common.files.sftp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SftpLocationInputTest : BaseTest() {

    private fun parse(
        host: String = "build.lan",
        port: String = "22",
        username: String = "darken",
        basePath: String = "",
    ) = SftpLocationInput.parse(
        host = host,
        port = port,
        username = username,
        basePath = basePath,
    )

    private fun issuesOf(result: SftpLocationInput.Result) =
        result.shouldBeInstanceOf<SftpLocationInput.Result.Invalid>().issues

    private fun parsedOf(result: SftpLocationInput.Result) =
        result.shouldBeInstanceOf<SftpLocationInput.Result.Valid>().parsed

    @Test
    fun `a plain host and username parse`() {
        parsedOf(parse()) shouldBe SftpLocationInput.Parsed(
            host = "build.lan",
            port = 22,
            username = "darken",
            basePath = "",
        )
    }

    @Test
    fun `an empty port falls back to 22`() {
        parsedOf(parse(port = " ")).port shouldBe 22
    }

    @Test
    fun `a port outside the valid range is rejected`() {
        issuesOf(parse(port = "0")) shouldBe listOf(SftpLocationInput.Issue.PortOutOfRange)
        issuesOf(parse(port = "65536")) shouldBe listOf(SftpLocationInput.Issue.PortOutOfRange)
        issuesOf(parse(port = "ssh")) shouldBe listOf(SftpLocationInput.Issue.PortOutOfRange)
    }

    @Test
    fun `bracketed and raw IPv6 are both accepted and stored unbracketed`() {
        parsedOf(parse(host = "[fe80::1]")).host shouldBe "fe80::1"
        parsedOf(parse(host = "fe80::1")).host shouldBe "fe80::1"
    }

    @Test
    fun `the host field takes only the host`() {
        issuesOf(parse(host = "sftp://build.lan")) shouldBe listOf(SftpLocationInput.Issue.HostNotBare)
        issuesOf(parse(host = "build.lan/srv")) shouldBe listOf(SftpLocationInput.Issue.HostNotBare)
        issuesOf(parse(host = "darken@build.lan")) shouldBe listOf(SftpLocationInput.Issue.HostNotBare)
        issuesOf(parse(host = "  ")) shouldBe listOf(SftpLocationInput.Issue.HostBlank)
        issuesOf(parse(host = "build lan")) shouldBe listOf(SftpLocationInput.Issue.HostMalformed)
    }

    @Test
    fun `a username is required and trimmed`() {
        issuesOf(parse(username = " ")) shouldBe listOf(SftpLocationInput.Issue.UsernameBlank)
        issuesOf(parse(username = "dar\nken")) shouldBe listOf(SftpLocationInput.Issue.UsernameMalformed)
        parsedOf(parse(username = " darken ")).username shouldBe "darken"
    }

    @Test
    fun `the base path is kept verbatim in all three forms`() {
        parsedOf(parse(basePath = "/srv/media")).basePath shouldBe "/srv/media"
        parsedOf(parse(basePath = "media/2024 ")).basePath shouldBe "media/2024 "
        parsedOf(parse(basePath = "../shared")).basePath shouldBe "../shared"
        parsedOf(parse(basePath = "   ")).basePath shouldBe "   "
    }

    @Test
    fun `a base path with NUL is rejected`() {
        issuesOf(parse(basePath = "a\u0000b")) shouldBe listOf(SftpLocationInput.Issue.BasePathMalformed)
    }

    @Test
    fun `every issue is reported at once`() {
        issuesOf(parse(host = "", port = "0", username = "")) shouldBe listOf(
            SftpLocationInput.Issue.HostBlank,
            SftpLocationInput.Issue.PortOutOfRange,
            SftpLocationInput.Issue.UsernameBlank,
        )
    }

    @Test
    fun `paths split on slashes only, never on backslashes`() {
        SftpLocationInput.splitPath("/photos//2024/") shouldBe listOf("photos", "2024")
        SftpLocationInput.splitPath("a\\b/c") shouldBe listOf("a\\b", "c")
        SftpLocationInput.splitPath("") shouldBe emptyList()
    }

    @Test
    fun `segments follow POSIX, not SMB name rules`() {
        SftpLocationInput.pathSegmentIssue("Budget:2026*?") shouldBe null
        SftpLocationInput.pathSegmentIssue("trailing.") shouldBe null
        SftpLocationInput.pathSegmentIssue("..") shouldBe SftpLocationInput.NameIssue.TRAVERSAL
        SftpLocationInput.pathSegmentIssue(".") shouldBe SftpLocationInput.NameIssue.TRAVERSAL
        SftpLocationInput.pathSegmentIssue(" ") shouldBe null
        SftpLocationInput.pathSegmentIssue("a\u0000b") shouldBe SftpLocationInput.NameIssue.MALFORMED
    }

    @Test
    fun `whitespace-only names and base paths are ordinary POSIX names`() {
        SftpLocationInput.pathSegmentIssue(" ") shouldBe null
        SftpLocationInput.pathSegmentIssue("  ") shouldBe null
        parsedOf(parse(basePath = "   ")).basePath shouldBe "   "
    }
}
