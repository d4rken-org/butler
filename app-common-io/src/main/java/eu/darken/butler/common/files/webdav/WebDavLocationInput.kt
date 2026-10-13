package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.WebDavEndpoint
import eu.darken.webdav.WebDavPath as ServerPath

/**
 * Parses and validates what a user types when adding a WebDAV server, and paths typed into the
 * breadcrumb bar below one.
 *
 * Path segments follow [ServerPath]: `\`, `%`, `?` or `#` are ordinary characters in a server's file names.
 */
object WebDavLocationInput {

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    enum class NameIssue {
        BLANK,
        TRAVERSAL,
        MALFORMED,
    }

    enum class Field {
        URL,
        USERNAME,
    }

    sealed interface Issue {
        val field: Field

        data object UrlBlank : Issue {
            override val field = Field.URL
        }

        /** No scheme at all, or one other than `http://` and `https://`. */
        data object UrlSchemeUnsupported : Issue {
            override val field = Field.URL
        }

        /** `https://alice:secret@cloud.example.com/`: credentials belong in their own fields. */
        data object UrlHasUserInfo : Issue {
            override val field = Field.URL
        }

        data object UrlHasQuery : Issue {
            override val field = Field.URL
        }

        data object UrlHasFragment : Issue {
            override val field = Field.URL
        }

        data object HostMalformed : Issue {
            override val field = Field.URL
        }

        data object PortOutOfRange : Issue {
            override val field = Field.URL
        }

        data object UsernameBlank : Issue {
            override val field = Field.USERNAME
        }

        /** A `:` cannot be sent in HTTP Basic credentials, control characters not at all. */
        data object UsernameMalformed : Issue {
            override val field = Field.USERNAME
        }
    }

    /**
     * [url] is normalized as [WebDavLocation.url] stores it, [host] is unbracketed and [port] is
     * explicit even when the URL omits it. [label] is null when blank, [username] is null for
     * [WebDavLocation.AuthType.ANONYMOUS].
     */
    data class Parsed(
        val url: String,
        val host: String,
        val port: Int,
        val label: String?,
        val authType: WebDavLocation.AuthType,
        val username: String?,
    )

    sealed interface Result {
        data class Valid(val parsed: Parsed) : Result
        data class Invalid(val issues: List<Issue>) : Result
    }

    /**
     * ```
     * parse(url = " HTTPS://[FE80::1]:8443/dav", label = "", authType = BASIC, username = " alice ")
     *   -> Parsed(url = "https://[fe80::1]:8443/dav/", host = "fe80::1", port = 8443, label = null,
     *             authType = BASIC, username = "alice")
     * ```
     */
    fun parse(
        url: String,
        label: String,
        authType: WebDavLocation.AuthType,
        username: String,
    ): Result {
        val issues = mutableListOf<Issue>()

        val endpoint = parseUrl(url.trim(), issues)

        val parsedUsername = when (authType) {
            WebDavLocation.AuthType.ANONYMOUS -> null
            WebDavLocation.AuthType.BASIC -> username.trim().also {
                when {
                    it.isEmpty() -> issues.add(Issue.UsernameBlank)
                    it.contains(':') || it.any { char -> char.isISOControl() } -> issues.add(Issue.UsernameMalformed)
                }
            }
        }

        if (issues.isNotEmpty()) return Result.Invalid(issues)

        return Result.Valid(
            Parsed(
                url = endpoint!!.url,
                host = endpoint.host,
                port = endpoint.port,
                label = label.trim().takeIf { it.isNotEmpty() },
                authType = authType,
                username = parsedUsername,
            ),
        )
    }

    /** Whether the form should warn that [url] travels unencrypted, judged while it is typed. */
    fun isCleartext(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

    /** `a//b/` is `["a", "b"]`; a `\` stays part of its name. */
    fun splitPath(raw: String): Segments = raw
        .split('/')
        .filter { it.isNotEmpty() }

    fun pathSegmentIssue(segment: String): NameIssue? = when {
        segment.isEmpty() -> NameIssue.BLANK
        segment == "." || segment == ".." -> NameIssue.TRAVERSAL
        !ServerPath.isValidSegment(segment) -> NameIssue.MALFORMED
        else -> null
    }

    /**
     * Names what is wrong with the parts of [raw] a user can get wrong, then leaves the host's syntax
     * to [WebDavEndpoint], the parser the connection uses.
     */
    private fun parseUrl(raw: String, issues: MutableList<Issue>): WebDavEndpoint? {
        if (raw.isEmpty()) return null.also { issues.add(Issue.UrlBlank) }

        val scheme = SCHEME.find(raw)?.groupValues?.get(1)?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: return null.also { issues.add(Issue.UrlSchemeUnsupported) }

        val rest = raw.substring(scheme.length + "://".length)
        val fragmentAt = rest.indexOf('#').takeIf { it >= 0 } ?: rest.length
        val queryAt = rest.indexOf('?').takeIf { it in 0 until fragmentAt } ?: fragmentAt
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: rest.length)

        val issueCount = issues.size
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort != authority) issues.add(Issue.UrlHasUserInfo)
        if (queryAt < fragmentAt) issues.add(Issue.UrlHasQuery)
        if (fragmentAt < rest.length) issues.add(Issue.UrlHasFragment)

        val portText = when {
            hostAndPort.startsWith('[') -> {
                val closing = hostAndPort.indexOf(']')
                val afterHost = if (closing < 0) null else hostAndPort.substring(closing + 1)
                when {
                    closing <= 1 || afterHost == null -> null.also { issues.add(Issue.HostMalformed) }
                    afterHost.isEmpty() -> null
                    afterHost.startsWith(':') -> afterHost.substring(1)
                    else -> null.also { issues.add(Issue.HostMalformed) }
                }
            }

            // An IPv6 literal needs its brackets, otherwise its colons read as a port.
            hostAndPort.count { it == ':' } > 1 || hostAndPort.substringBefore(':').isEmpty() -> {
                null.also { issues.add(Issue.HostMalformed) }
            }

            else -> hostAndPort.substringAfter(':', missingDelimiterValue = "")
        }
        // An empty port means the scheme's default, as in `https://cloud.example.com:/`.
        val portValid = portText.isNullOrEmpty() ||
            (portText.all { it.isDigit() } && portText.toIntOrNull()?.takeIf { it in 1..65535 } != null)
        if (!portValid) issues.add(Issue.PortOutOfRange)

        if (issues.size > issueCount) return null
        return try {
            WebDavEndpoint(raw)
        } catch (e: IllegalArgumentException) {
            null.also { issues.add(Issue.HostMalformed) }
        }
    }
}
