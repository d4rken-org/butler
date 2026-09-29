package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.ssh.SftpPath as ServerPath

/**
 * Parses and validates what a user types when adding an SFTP server, and paths typed into the
 * breadcrumb bar below one.
 *
 * POSIX rules only: `\`, `:` or `*` are ordinary characters in a server's file names.
 */
object SftpLocationInput {

    const val DEFAULT_PORT = SftpLocation.DEFAULT_PORT

    private val HOST_CHARS = Regex("^[A-Za-z0-9._-]+$")

    enum class NameIssue {
        BLANK,
        TRAVERSAL,
        MALFORMED,
    }

    enum class Field {
        HOST,
        PORT,
        USERNAME,
        BASE_PATH,
    }

    sealed interface Issue {
        val field: Field

        data object HostBlank : Issue {
            override val field = Field.HOST
        }

        /** A scheme, a path or a `user@` prefix typed into the host field. */
        data object HostNotBare : Issue {
            override val field = Field.HOST
        }

        data object HostMalformed : Issue {
            override val field = Field.HOST
        }

        data object PortOutOfRange : Issue {
            override val field = Field.PORT
        }

        data object UsernameBlank : Issue {
            override val field = Field.USERNAME
        }

        data object UsernameMalformed : Issue {
            override val field = Field.USERNAME
        }

        data object BasePathMalformed : Issue {
            override val field = Field.BASE_PATH
        }
    }

    /** [basePath] is the raw form [SftpLocation.basePath] stores, `""` for the server's initial directory. */
    data class Parsed(
        val host: String,
        val port: Int,
        val username: String,
        val basePath: String,
    )

    sealed interface Result {
        data class Valid(val parsed: Parsed) : Result
        data class Invalid(val issues: List<Issue>) : Result
    }

    /**
     * ```
     * parse(host = "[fe80::1]", port = "", username = "darken", basePath = "media")
     *   -> Parsed(host = "fe80::1", port = 22, username = "darken", basePath = "media")
     * ```
     */
    fun parse(
        host: String,
        port: String,
        username: String,
        basePath: String,
    ): Result {
        val issues = mutableListOf<Issue>()

        val trimmedHost = host.trim()
        val parsedHost = when {
            trimmedHost.isEmpty() -> null.also { issues.add(Issue.HostBlank) }
            trimmedHost.contains("://") || trimmedHost.contains('/') || trimmedHost.contains('@') -> {
                null.also { issues.add(Issue.HostNotBare) }
            }

            else -> normalizeHost(trimmedHost).also { if (it == null) issues.add(Issue.HostMalformed) }
        }

        val trimmedPort = port.trim()
        val parsedPort = when {
            trimmedPort.isEmpty() -> DEFAULT_PORT
            else -> trimmedPort.toIntOrNull()?.takeIf { it in 1..65535 }
                .also { if (it == null) issues.add(Issue.PortOutOfRange) }
        }

        val trimmedUsername = username.trim()
        when {
            trimmedUsername.isEmpty() -> issues.add(Issue.UsernameBlank)
            trimmedUsername.any { it.isISOControl() } -> issues.add(Issue.UsernameMalformed)
        }

        // Stored verbatim: the server resolves it, so `../shared` or a name with spaces stay intact.
        val parsedBasePath = basePath
        if (!SftpLocation.isValidBasePath(parsedBasePath)) issues.add(Issue.BasePathMalformed)

        if (issues.isNotEmpty()) return Result.Invalid(issues)

        return Result.Valid(
            Parsed(
                host = parsedHost!!,
                port = parsedPort!!,
                username = trimmedUsername,
                basePath = parsedBasePath,
            ),
        )
    }

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

    /** Accepts both `[fe80::1]` and `fe80::1`, storing the unbracketed form. */
    private fun normalizeHost(raw: String): String? {
        val unbracketed = when {
            raw.startsWith("[") && raw.endsWith("]") -> raw.substring(1, raw.length - 1)
            else -> raw
        }
        if (unbracketed.isEmpty()) return null
        if (unbracketed.contains(':')) {
            val isIpv6 = unbracketed.all { it.isDigit() || it in "abcdefABCDEF:.%" } &&
                unbracketed.count { it == ':' } >= 2
            return if (isIpv6) unbracketed else null
        }
        return if (HOST_CHARS.matches(unbracketed)) unbracketed else null
    }
}
