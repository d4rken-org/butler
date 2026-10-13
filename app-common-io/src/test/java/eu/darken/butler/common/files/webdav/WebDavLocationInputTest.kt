package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.webdav.WebDavLocationInput.Issue
import eu.darken.butler.common.files.webdav.location.WebDavLocation.AuthType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class WebDavLocationInputTest : BaseTest() {

    private fun parse(
        url: String = "https://cloud.example.com/remote.php/dav/files/alice",
        label: String = "",
        authType: AuthType = AuthType.BASIC,
        username: String = "alice",
    ) = WebDavLocationInput.parse(
        url = url,
        label = label,
        authType = authType,
        username = username,
    )

    private fun issuesOf(result: WebDavLocationInput.Result) =
        result.shouldBeInstanceOf<WebDavLocationInput.Result.Invalid>().issues

    private fun parsedOf(result: WebDavLocationInput.Result) =
        result.shouldBeInstanceOf<WebDavLocationInput.Result.Valid>().parsed

    @Test
    fun `a Nextcloud URL is normalized to a collection`() {
        parsedOf(parse()) shouldBe WebDavLocationInput.Parsed(
            url = "https://cloud.example.com/remote.php/dav/files/alice/",
            host = "cloud.example.com",
            port = 443,
            label = null,
            authType = AuthType.BASIC,
            username = "alice",
        )
    }

    @Test
    fun `scheme and host case and surrounding whitespace are normalized`() {
        parsedOf(parse(url = "  HTTPS://Cloud.Example.com/dav/  ")).url shouldBe "https://cloud.example.com/dav/"
        parsedOf(parse(url = "https://cloud.example.com")).url shouldBe "https://cloud.example.com/"
        parsedOf(parse(url = "https://cloud.example.com/dav/")).url shouldBe "https://cloud.example.com/dav/"
    }

    @Test
    fun `a path with spaces is percent-encoded`() {
        parsedOf(parse(url = "https://nas.local/dav/files/alice smith")).url shouldBe
            "https://nas.local/dav/files/alice%20smith/"
    }

    @Test
    fun `an explicit port is kept, the default port dropped`() {
        parsedOf(parse(url = "https://nas.local:8443/dav")).let {
            it.url shouldBe "https://nas.local:8443/dav/"
            it.port shouldBe 8443
        }
        parsedOf(parse(url = "https://nas.local:443/dav")).url shouldBe "https://nas.local/dav/"
        parsedOf(parse(url = "http://nas.local/dav")).port shouldBe 80
        parsedOf(parse(url = "https://nas.local:/dav")).port shouldBe 443
    }

    @Test
    fun `an IPv6 literal host needs brackets and is stored unbracketed`() {
        parsedOf(parse(url = "https://[FE80::1]:8443/dav")).let {
            it.url shouldBe "https://[fe80::1]:8443/dav/"
            it.host shouldBe "fe80::1"
            it.port shouldBe 8443
        }
        parsedOf(parse(url = "http://[::1]/")).host shouldBe "::1"
        issuesOf(parse(url = "https://fe80::1/dav")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https://[fe80::1/dav")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https://[]/dav")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https://[fe80::1]x/dav")) shouldBe listOf(Issue.HostMalformed)
    }

    @Test
    fun `a blank URL is reported`() {
        issuesOf(parse(url = "  ")) shouldBe listOf(Issue.UrlBlank)
    }

    @Test
    fun `only http and https URLs are accepted`() {
        issuesOf(parse(url = "cloud.example.com/dav")) shouldBe listOf(Issue.UrlSchemeUnsupported)
        issuesOf(parse(url = "ftp://cloud.example.com/dav")) shouldBe listOf(Issue.UrlSchemeUnsupported)
        issuesOf(parse(url = "webdav://cloud.example.com/dav")) shouldBe listOf(Issue.UrlSchemeUnsupported)
        issuesOf(parse(url = "https:cloud.example.com")) shouldBe listOf(Issue.UrlSchemeUnsupported)
    }

    @Test
    fun `userinfo, query and fragment are each reported`() {
        issuesOf(parse(url = "https://alice:secret@cloud.example.com/dav")) shouldBe listOf(Issue.UrlHasUserInfo)
        issuesOf(parse(url = "https://cloud.example.com/dav?x=1")) shouldBe listOf(Issue.UrlHasQuery)
        issuesOf(parse(url = "https://cloud.example.com/dav?")) shouldBe listOf(Issue.UrlHasQuery)
        issuesOf(parse(url = "https://cloud.example.com/dav#top")) shouldBe listOf(Issue.UrlHasFragment)
        issuesOf(parse(url = "https://cloud.example.com/dav#top?x")) shouldBe listOf(Issue.UrlHasFragment)
        issuesOf(parse(url = "https://a@cloud.example.com/dav?x#y")) shouldBe listOf(
            Issue.UrlHasUserInfo,
            Issue.UrlHasQuery,
            Issue.UrlHasFragment,
        )
    }

    @Test
    fun `a malformed host is reported`() {
        issuesOf(parse(url = "https://")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https:///dav")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https://:8443/dav")) shouldBe listOf(Issue.HostMalformed)
        issuesOf(parse(url = "https://cloud example.com/dav")) shouldBe listOf(Issue.HostMalformed)
    }

    @Test
    fun `a port outside the valid range is rejected`() {
        issuesOf(parse(url = "https://nas.local:0/dav")) shouldBe listOf(Issue.PortOutOfRange)
        issuesOf(parse(url = "https://nas.local:65536/dav")) shouldBe listOf(Issue.PortOutOfRange)
        issuesOf(parse(url = "https://nas.local:https/dav")) shouldBe listOf(Issue.PortOutOfRange)
        issuesOf(parse(url = "https://nas.local:+443/dav")) shouldBe listOf(Issue.PortOutOfRange)
        issuesOf(parse(url = "https://[::1]:99999/dav")) shouldBe listOf(Issue.PortOutOfRange)
    }

    @Test
    fun `Basic auth needs a usable username`() {
        issuesOf(parse(username = " ")) shouldBe listOf(Issue.UsernameBlank)
        issuesOf(parse(username = "alice:smith")) shouldBe listOf(Issue.UsernameMalformed)
        issuesOf(parse(username = "ali\nce")) shouldBe listOf(Issue.UsernameMalformed)
        parsedOf(parse(username = " alice ")).username shouldBe "alice"
    }

    @Test
    fun `anonymous access ignores the username`() {
        parsedOf(parse(authType = AuthType.ANONYMOUS, username = "")).let {
            it.username shouldBe null
            it.authType shouldBe AuthType.ANONYMOUS
        }
        parsedOf(parse(authType = AuthType.ANONYMOUS, username = "alice")).username shouldBe null
    }

    @Test
    fun `a blank label is no label, others are trimmed`() {
        parsedOf(parse(label = "   ")).label shouldBe null
        parsedOf(parse(label = " Nextcloud ")).label shouldBe "Nextcloud"
    }

    @Test
    fun `every issue is reported at once, each with its field`() {
        val issues = issuesOf(parse(url = "ftp://x", username = ""))
        issues shouldBe listOf(Issue.UrlSchemeUnsupported, Issue.UsernameBlank)
        issues.map { it.field } shouldBe listOf(WebDavLocationInput.Field.URL, WebDavLocationInput.Field.USERNAME)
    }

    @Test
    fun `cleartext is detected from the scheme`() {
        WebDavLocationInput.isCleartext("http://nas.local/dav") shouldBe true
        WebDavLocationInput.isCleartext("  HTTP://nas.local") shouldBe true
        WebDavLocationInput.isCleartext("https://nas.local/dav") shouldBe false
        WebDavLocationInput.isCleartext("nas.local") shouldBe false
        WebDavLocationInput.isCleartext("") shouldBe false
    }

    @Test
    fun `paths split on slashes only, never on backslashes`() {
        WebDavLocationInput.splitPath("/photos//2024/") shouldBe listOf("photos", "2024")
        WebDavLocationInput.splitPath("a\\b/c") shouldBe listOf("a\\b", "c")
        WebDavLocationInput.splitPath("") shouldBe emptyList()
    }

    @Test
    fun `segments follow the WebDAV path rules`() {
        WebDavLocationInput.pathSegmentIssue("100% ?#done") shouldBe null
        WebDavLocationInput.pathSegmentIssue(" ") shouldBe null
        WebDavLocationInput.pathSegmentIssue("") shouldBe WebDavLocationInput.NameIssue.BLANK
        WebDavLocationInput.pathSegmentIssue("..") shouldBe WebDavLocationInput.NameIssue.TRAVERSAL
        WebDavLocationInput.pathSegmentIssue(".") shouldBe WebDavLocationInput.NameIssue.TRAVERSAL
        WebDavLocationInput.pathSegmentIssue("a\u0000b") shouldBe WebDavLocationInput.NameIssue.MALFORMED
    }

    @Test
    fun `a parsed URL is accepted by the location model`() {
        val parsed = parsedOf(parse(url = "https://[fe80::1]:8443/remote.php/dav/files/alice smith"))
        testWebDavLocation(id = Uuid.random(), url = parsed.url).url shouldBe parsed.url
    }
}
