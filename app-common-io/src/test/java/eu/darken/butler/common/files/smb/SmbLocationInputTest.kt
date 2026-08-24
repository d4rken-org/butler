package eu.darken.butler.common.files.smb

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SmbLocationInputTest : BaseTest() {

    private fun parse(
        host: String = "nas.local",
        port: String = "445",
        share: String = "media",
        basePath: String = "",
        username: String = "darken",
        requireUsername: Boolean = false,
    ) = SmbLocationInput.parse(
        host = host,
        port = port,
        share = share,
        basePath = basePath,
        username = username,
        requireUsername = requireUsername,
    )

    private fun issuesOf(result: SmbLocationInput.Result) =
        result.shouldBeInstanceOf<SmbLocationInput.Result.Invalid>().issues

    private fun parsedOf(result: SmbLocationInput.Result) =
        result.shouldBeInstanceOf<SmbLocationInput.Result.Valid>().parsed

    @Test
    fun `a plain host and share parse`() {
        val parsed = parsedOf(parse())
        parsed.host shouldBe "nas.local"
        parsed.port shouldBe 445
        parsed.share shouldBe "media"
        parsed.basePath shouldBe emptyList()
    }

    @Test
    fun `an empty port falls back to the default`() {
        parsedOf(parse(port = "")).port shouldBe SmbLocationInput.DEFAULT_PORT
    }

    @Test
    fun `a port outside the valid range is rejected`() {
        issuesOf(parse(port = "0")) shouldBe listOf(SmbLocationInput.Issue.PortOutOfRange)
        issuesOf(parse(port = "65536")) shouldBe listOf(SmbLocationInput.Issue.PortOutOfRange)
        issuesOf(parse(port = "nope")) shouldBe listOf(SmbLocationInput.Issue.PortOutOfRange)
    }

    @Test
    fun `bracketed and raw IPv6 are both accepted and stored unbracketed`() {
        parsedOf(parse(host = "[fe80::1]")).host shouldBe "fe80::1"
        parsedOf(parse(host = "fe80::1")).host shouldBe "fe80::1"
    }

    @Test
    fun `IPv4 and hostnames are accepted`() {
        parsedOf(parse(host = "192.168.1.10")).host shouldBe "192.168.1.10"
        parsedOf(parse(host = "nas-01.fritz.box")).host shouldBe "nas-01.fritz.box"
    }

    @Test
    fun `a host with a scheme or a path is rejected`() {
        issuesOf(parse(host = "smb://nas.local")) shouldBe listOf(SmbLocationInput.Issue.HostNotBare)
        issuesOf(parse(host = "\\\\nas.local")) shouldBe listOf(SmbLocationInput.Issue.HostNotBare)
        issuesOf(parse(host = "nas.local/media")) shouldBe listOf(SmbLocationInput.Issue.HostNotBare)
    }

    @Test
    fun `a blank host is reported as blank`() {
        issuesOf(parse(host = "  ")) shouldBe listOf(SmbLocationInput.Issue.HostBlank)
    }

    @Test
    fun `a share must be a single component`() {
        issuesOf(parse(share = "media/movies")) shouldBe listOf(SmbLocationInput.Issue.ShareNotSingleComponent)
        issuesOf(parse(share = "")) shouldBe listOf(SmbLocationInput.Issue.ShareBlank)
    }

    @Test
    fun `a share with server-rejected characters is malformed`() {
        issuesOf(parse(share = "med:ia")) shouldBe listOf(SmbLocationInput.Issue.ShareMalformed)
    }

    @Test
    fun `a base path parses both separator styles`() {
        parsedOf(parse(basePath = "movies/2024")).basePath shouldBe listOf("movies", "2024")
        parsedOf(parse(basePath = "\\movies\\2024")).basePath shouldBe listOf("movies", "2024")
        parsedOf(parse(basePath = "//movies//")).basePath shouldBe listOf("movies")
    }

    @Test
    fun `a base path with traversal components is rejected`() {
        issuesOf(parse(basePath = "movies/../etc")) shouldBe listOf(SmbLocationInput.Issue.BasePathMalformed)
        issuesOf(parse(basePath = "movies/.")) shouldBe listOf(SmbLocationInput.Issue.BasePathMalformed)
    }

    @Test
    fun `a username is only required when asked for`() {
        issuesOf(parse(username = "", requireUsername = true)) shouldBe
            listOf(SmbLocationInput.Issue.UsernameBlank)
        parsedOf(parse(username = "", requireUsername = false)).username shouldBe null
    }

    @Test
    fun `path segments are checked structurally, not by Windows rules`() {
        SmbLocationInput.pathSegmentIssue("normal") shouldBe null
        SmbLocationInput.pathSegmentIssue("weird:name*") shouldBe null
        SmbLocationInput.pathSegmentIssue("") shouldBe SmbLocationInput.NameIssue.BLANK
        SmbLocationInput.pathSegmentIssue("..") shouldBe SmbLocationInput.NameIssue.TRAVERSAL
        SmbLocationInput.pathSegmentIssue("a/b") shouldBe SmbLocationInput.NameIssue.BLANK
        SmbLocationInput.pathSegmentIssue("a\\b") shouldBe SmbLocationInput.NameIssue.BLANK
    }

    @Test
    fun `whole-name rules cover what characters cannot`() {
        SmbLocationInput.nameIssue("file.txt") shouldBe null
        SmbLocationInput.nameIssue("  ") shouldBe SmbLocationInput.NameIssue.BLANK
        SmbLocationInput.nameIssue(".") shouldBe SmbLocationInput.NameIssue.TRAVERSAL
        SmbLocationInput.nameIssue("trailing.") shouldBe SmbLocationInput.NameIssue.TRAILING_DOT_OR_SPACE
        SmbLocationInput.nameIssue("trailing ") shouldBe SmbLocationInput.NameIssue.TRAILING_DOT_OR_SPACE
    }
}
