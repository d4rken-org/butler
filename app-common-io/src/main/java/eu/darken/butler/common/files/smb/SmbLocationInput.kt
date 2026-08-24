package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.extensions.Segments

/**
 * Parses and validates the raw components a user types when adding a network location.
 *
 * Two rule sets live here, they are deliberately different:
 * - [pathSegmentIssue] is *structural*: what a path segment must satisfy so that a segment list can
 *   be turned back into an SMB path unambiguously. Applied to every [eu.darken.butler.common.files.SmbPath]
 *   segment, including names that were read from a server, so it must not reject names the server
 *   already accepted.
 * - [nameIssue] additionally rejects the characters Windows/SMB servers refuse in *new* names. Only
 *   applied to names the user picks (share, new file/folder names).
 */
object SmbLocationInput {

    const val DEFAULT_PORT = 445

    /** Characters an SMB server rejects in a new file or folder name. */
    val RESTRICTED_NAME_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    private val STRUCTURAL_SEGMENT_CHARS = setOf('\\', '/', '\u0000')

    private val HOST_CHARS = Regex("^[A-Za-z0-9._-]+$")

    /** A whole name is unusable regardless of the characters it is made of. */
    enum class NameIssue {
        BLANK,
        TRAVERSAL,
        TRAILING_DOT_OR_SPACE,
    }

    enum class Field {
        HOST,
        PORT,
        SHARE,
        BASE_PATH,
        USERNAME,
    }

    sealed interface Issue {
        val field: Field

        data object HostBlank : Issue {
            override val field = Field.HOST
        }

        /** The host field carries a `smb://`/`\\` prefix or a path, only the host belongs here. */
        data object HostNotBare : Issue {
            override val field = Field.HOST
        }

        data object HostMalformed : Issue {
            override val field = Field.HOST
        }

        data object PortOutOfRange : Issue {
            override val field = Field.PORT
        }

        data object ShareBlank : Issue {
            override val field = Field.SHARE
        }

        /** A share is a single component, `share/sub` belongs in the base directory field. */
        data object ShareNotSingleComponent : Issue {
            override val field = Field.SHARE
        }

        data object ShareMalformed : Issue {
            override val field = Field.SHARE
        }

        data object BasePathMalformed : Issue {
            override val field = Field.BASE_PATH
        }

        data object UsernameBlank : Issue {
            override val field = Field.USERNAME
        }
    }

    data class Parsed(
        val host: String,
        val port: Int,
        val share: String,
        val basePath: Segments,
        val domain: String?,
        val username: String?,
    )

    sealed interface Result {
        data class Valid(val parsed: Parsed) : Result
        data class Invalid(val issues: List<Issue>) : Result
    }

    /**
     * ```
     * parse(host = "[fe80::1]", port = "445", share = "media", basePath = "\\movies\\2024")
     *   -> Parsed(host = "fe80::1", port = 445, share = "media", basePath = ["movies", "2024"])
     * ```
     */
    fun parse(
        host: String,
        port: String,
        share: String,
        basePath: String,
        domain: String = "",
        username: String = "",
        requireUsername: Boolean = false,
    ): Result {
        val issues = mutableListOf<Issue>()

        val trimmedHost = host.trim()
        val parsedHost = when {
            trimmedHost.isEmpty() -> null.also { issues.add(Issue.HostBlank) }
            trimmedHost.contains("://") || trimmedHost.startsWith("\\\\") -> {
                null.also { issues.add(Issue.HostNotBare) }
            }

            trimmedHost.contains('/') -> null.also { issues.add(Issue.HostNotBare) }
            else -> normalizeHost(trimmedHost).also { if (it == null) issues.add(Issue.HostMalformed) }
        }

        val trimmedPort = port.trim()
        val parsedPort = when {
            trimmedPort.isEmpty() -> DEFAULT_PORT
            else -> trimmedPort.toIntOrNull()?.takeIf { it in 1..65535 }
                .also { if (it == null) issues.add(Issue.PortOutOfRange) }
        }

        val trimmedShare = share.trim().trim('\\', '/')
        val parsedShare = when {
            trimmedShare.isEmpty() -> null.also { issues.add(Issue.ShareBlank) }
            trimmedShare.contains('/') || trimmedShare.contains('\\') -> {
                null.also { issues.add(Issue.ShareNotSingleComponent) }
            }

            nameIssue(trimmedShare) != null -> null.also { issues.add(Issue.ShareMalformed) }
            trimmedShare.any { it in RESTRICTED_NAME_CHARS } -> null.also { issues.add(Issue.ShareMalformed) }
            else -> trimmedShare
        }

        val parsedBasePath = splitPath(basePath)
        if (parsedBasePath.any { pathSegmentIssue(it) != null }) issues.add(Issue.BasePathMalformed)

        val trimmedUsername = username.trim()
        if (requireUsername && trimmedUsername.isEmpty()) issues.add(Issue.UsernameBlank)

        if (issues.isNotEmpty()) return Result.Invalid(issues)

        return Result.Valid(
            Parsed(
                host = parsedHost!!,
                port = parsedPort!!,
                share = parsedShare!!,
                basePath = parsedBasePath,
                domain = normalizeDomain(domain),
                username = trimmedUsername.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /** No domain and a blank domain mean the same thing, so they must compare equal. */
    fun normalizeDomain(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** Splits on both separator styles so a pasted `\\srv\media` tail and a typed `a/b` both work. */
    fun splitPath(raw: String): Segments = raw
        .split('/', '\\')
        .filter { it.isNotEmpty() }

    /** Structural check, see the class doc for why this is weaker than [nameIssue]. */
    fun pathSegmentIssue(segment: String): NameIssue? = when {
        segment.isBlank() -> NameIssue.BLANK
        segment == "." || segment == ".." -> NameIssue.TRAVERSAL
        segment.any { it in STRUCTURAL_SEGMENT_CHARS } -> NameIssue.BLANK
        else -> null
    }

    /** Whole-name check for names the user picks. Character rules are checked separately. */
    fun nameIssue(name: String): NameIssue? = when {
        name.isBlank() -> NameIssue.BLANK
        name == "." || name == ".." -> NameIssue.TRAVERSAL
        name.last() == '.' || name.last() == ' ' -> NameIssue.TRAILING_DOT_OR_SPACE
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
            // Raw IPv6, ':' is not legal in any other host form we accept
            val isIpv6 = unbracketed.all { it.isDigit() || it in "abcdefABCDEF:.%" } && unbracketed.count { it == ':' } >= 2
            return if (isIpv6) unbracketed else null
        }
        return if (HOST_CHARS.matches(unbracketed)) unbracketed else null
    }
}
