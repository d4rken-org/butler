package eu.darken.butler.architecture

import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

/**
 * Bans the raw `androidx.activity.compose.BackHandler` everywhere except four documented sites.
 *
 * `BackHandler` dispatches LIFO — the most recently registered enabled callback wins. Workspace
 * pages are composed deeper than the tab manager overlay's dismiss handler, so a raw handler inside
 * anything that renders in a pane outranks it and back closes the focused tab while the overlay is
 * open.
 *
 * [eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler] closes that hole by gating on
 * `LocalLayerActive`, which the pane layer host turns off for every pane while the overlay is up.
 * That only holds as long as nothing registers a raw handler — which is what this test enforces.
 *
 * The scan covers every module in `settings.gradle`, not just the workspace ones: shared composables
 * in `app-common` (error dialogs, sheets) render inside panes too and would reintroduce the bug just
 * as effectively.
 *
 * A thin wrapper around an exempt handler would slip past a purely textual ban, so the one wrapper
 * that exists — `ManagerOverlayBackHandler` — is pinned to its host as well.
 */
class RawBackHandlerBanTest : BaseTest() {

    @Test
    fun `raw BackHandler stays out of production code`() {
        val scanned = scanProductionSources()

        val violations = scanned.hitsByPath
            .filterKeys { it !in ALLOWLIST }
            .keys
            .sorted()

        if (violations.isNotEmpty()) throw AssertionError(violationMessage(violations))
    }

    /**
     * File-wide exemptions would let an exempt file collect further raw handlers unnoticed, so each
     * one is pinned to the number of occurrences it is allowed to have.
     */
    @Test
    fun `allowlisted files keep exactly their known raw BackHandler occurrences`() {
        val scanned = scanProductionSources()

        val problems = ALLOWLIST.entries.mapNotNull { (path, entry) ->
            val file = File(repoRoot, path)
            val actual = scanned.hitsByPath[path] ?: 0
            when {
                !file.isFile -> "$path — file no longer exists"
                actual == 0 -> "$path — no raw BackHandler left, drop the entry"
                actual != entry.occurrences ->
                    "$path — expected ${entry.occurrences} occurrence(s), found $actual"
                else -> null
            }
        }.sorted()

        if (problems.isNotEmpty()) {
            throw AssertionError(
                "The raw BackHandler allowlist no longer matches reality:\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\n\nAn occurrence is a qualified reference (import or fully qualified call) " +
                    "plus every bare `BackHandler` usage once the class is in scope.\n" +
                    "More than expected means a NEW raw handler slipped into an exempt file — the " +
                    "exemption covers the documented one only, not the whole file. Fewer (or none) " +
                    "means the entry is stale and must be removed, otherwise it silently shields " +
                    "whatever moves into that path later.",
            )
        }
    }

    /**
     * The exempt overlay dismisser is wrapped in a composable, and calling that wrapper registers an
     * ungated raw handler without adding a single raw occurrence for the scan above to find. So the
     * wrapper is pinned to its host too.
     */
    @Test
    fun `the ungated overlay back handler is only composed by its host`() {
        val scanned = scanProductionSources()

        val misplaced = scanned.overlayWrapperByPath.keys.filter { it != OVERLAY_DISMISSER }.sorted()
        val inHost = scanned.overlayWrapperByPath[OVERLAY_DISMISSER] ?: 0

        if (misplaced.isNotEmpty() || inHost != OVERLAY_WRAPPER_OCCURRENCES) {
            throw AssertionError(
                buildString {
                    appendLine("`ManagerOverlayBackHandler` is not where it belongs.")
                    appendLine()
                    if (misplaced.isNotEmpty()) {
                        appendLine("Composed outside its host in:")
                        misplaced.forEach { appendLine("  $it") }
                        appendLine()
                    }
                    if (inHost != OVERLAY_WRAPPER_OCCURRENCES) {
                        appendLine(
                            "$OVERLAY_DISMISSER: expected $OVERLAY_WRAPPER_OCCURRENCES " +
                                "occurrence(s) (the declaration and the host's call), found $inHost.",
                        )
                        appendLine()
                    }
                    appendLine("That composable registers an UNGATED raw BackHandler — it is exempt")
                    appendLine("only because WorkspacesScreenHost composes it above the workspace")
                    appendLine("content, where it loses every LIFO race. Composed inside the")
                    appendLine("workspace tree it outranks the pane handlers and back closes the")
                    appendLine("focused TAB with the manager overlay open, which is the exact bug")
                    appendLine("this file guards against.")
                    appendLine()
                    append("Use `eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler` instead.")
                },
            )
        }
    }

    /**
     * A guard that scans nothing is worse than no guard. A global floor is not enough: `app` and
     * `app-workspace` alone clear any sensible one, so every page module could drop out of the walk
     * unnoticed. Each module has to show up with files of its own.
     */
    @Test
    fun `every declared module actually gets scanned`() {
        val scanned = scanProductionSources()

        val empty = scanned.filesByModule.filterValues { it == 0 }.keys.sorted()
        if (empty.isNotEmpty()) {
            throw AssertionError(
                "No Kotlin files were collected for $empty. Their source layout changed — fix the " +
                    "walk, a module that silently contributes nothing is unguarded.",
            )
        }

        val missingWorkspaceModules = REQUIRED_WORKSPACE_MODULES - scanned.filesByModule.keys
        if (missingWorkspaceModules.isNotEmpty()) {
            throw AssertionError(
                "settings.gradle no longer declares ${missingWorkspaceModules.sorted()}. If those " +
                    "modules were intentionally removed, drop them from REQUIRED_WORKSPACE_MODULES; " +
                    "otherwise their pages just left the scan.",
            )
        }

        val total = scanned.filesByModule.values.sum()
        if (total < MIN_SCANNED_FILES) {
            throw AssertionError(
                "Only $total Kotlin files were scanned, expected at least $MIN_SCANNED_FILES. " +
                    "Per-module counts: ${scanned.filesByModule.toSortedMap()}",
            )
        }
    }

    private fun violationMessage(violations: List<String>): String = buildString {
        appendLine("Raw BackHandler found outside the allowlist:")
        appendLine()
        violations.forEach { appendLine("  $it") }
        appendLine()
        appendLine("`androidx.activity.compose.BackHandler` dispatches LIFO — the most recently")
        appendLine("registered enabled callback wins. Anything rendering inside a workspace pane is")
        appendLine("composed deeper than the tab manager overlay's dismiss handler in")
        appendLine("  $OVERLAY_DISMISSER")
        appendLine("so a raw handler there outranks it: pressing back while the tab manager overlay")
        appendLine("is open then closes the focused TAB instead of dismissing the overlay,")
        appendLine("destroying the user's state.")
        appendLine()
        appendLine("Use `eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler` instead. It is")
        appendLine("gated on `LocalLayerActive`, which the pane layer host turns off for every pane")
        appendLine("while the overlay is visible, so page handlers cannot win.")
        appendLine()
        appendLine("If this genuinely is an exception (outside the workspace tree, or the wrapper")
        append("itself), add it to ALLOWLIST in this test with the reason and its occurrence count.")
    }

    private class Scan(
        val filesByModule: Map<String, Int>,
        val hitsByPath: Map<String, Int>,
        val overlayWrapperByPath: Map<String, Int>,
    )

    /**
     * Every module `settings.gradle` declares, so a new module is covered the day it is added and a
     * module can only leave the scan by leaving the build.
     */
    private fun scanProductionSources(): Scan {
        val settings = File(repoRoot, "settings.gradle")
        val modules = declaredModules(settings.readText()) - TEST_SUPPORT_MODULES

        if (modules.isEmpty()) {
            throw AssertionError("No `include ':module'` lines found in $settings — parse broken.")
        }

        val filesByModule = mutableMapOf<String, Int>()
        val hitsByPath = mutableMapOf<String, Int>()
        val overlayWrapperByPath = mutableMapOf<String, Int>()

        modules.forEach { module ->
            val src = File(repoRoot, "${module.replace(':', '/')}/src")
            if (!File(src, "main").isDirectory) {
                throw AssertionError("$module has no src/main — the source layout changed.")
            }
            // Every production source set (main plus flavor/build-type ones), never a test one:
            // tests may use the raw handler to prove the wrapper's own behaviour.
            val files = src.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
                .flatMap { sourceSet ->
                    sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }

            filesByModule[module] = files.size
            files.forEach { file ->
                val code = file.readText().withoutCommentsAndStringLiterals()
                val hits = code.rawBackHandlerOccurrences()
                if (hits > 0) hitsByPath[file.relativePath()] = hits
                val wrapper = OVERLAY_WRAPPER_REFERENCE.findAll(code).count()
                if (wrapper > 0) overlayWrapperByPath[file.relativePath()] = wrapper
            }
        }

        return Scan(filesByModule, hitsByPath, overlayWrapperByPath)
    }

    /**
     * Groovy accepts `include ':a'`, `include(':a')`, comma-separated lists and nested `:group:name`
     * paths, so pull every quoted project path off each include statement rather than matching one
     * shape. A module declared in a form the parser does not know would silently leave the scan.
     */
    private fun declaredModules(settings: String): List<String> = INCLUDE_STATEMENT
        .findAll(settings)
        .flatMap { statement -> PROJECT_PATH.findAll(statement.groupValues[1]) }
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    /**
     * Counts raw handler occurrences, ignoring comments and string literals — a KDoc that merely
     * names the API must not fail the build, that is how guards end up deleted.
     *
     * Covers the qualified reference (plain import, aliased import, fully qualified call with any
     * spacing and with parentheses or a trailing lambda) and, once the class is in scope through any
     * import form including a wildcard, every bare usage of the name.
     */
    private fun String.rawBackHandlerOccurrences(): Int {
        val qualified = QUALIFIED_REFERENCE.findAll(this).count()
        val inScope = qualified > 0 || WILDCARD_IMPORT.containsMatchIn(this)
        val bare = if (inScope) BARE_REFERENCE.findAll(this).count() else 0
        return qualified + bare
    }

    /** Replaces comments and string literals with blanks, so tokens can never be joined together. */
    private fun String.withoutCommentsAndStringLiterals(): String {
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val char = this[i]
            val next = if (i + 1 < length) this[i + 1] else ' '
            when {
                char == '/' && next == '/' -> {
                    while (i < length && this[i] != '\n') i++
                    out.append(' ')
                }

                char == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < length && !(this[i] == '*' && this[i + 1] == '/')) i++
                    i = minOf(i + 2, length)
                    out.append(' ')
                }

                startsWith(RAW_STRING, i) -> {
                    i += RAW_STRING.length
                    while (i < length && !startsWith(RAW_STRING, i)) i++
                    i = minOf(i + RAW_STRING.length, length)
                    out.append(' ')
                }

                char == '"' || char == '\'' -> {
                    i++
                    while (i < length && this[i] != char) {
                        if (this[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append(' ')
                }

                else -> {
                    out.append(char)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun File.relativePath(): String = relativeTo(repoRoot).invariantSeparatorsPath

    /**
     * Gradle runs unit tests with the module directory as working directory, so walk up until the
     * settings file identifies the repo root instead of assuming a fixed depth.
     */
    private val repoRoot: File by lazy {
        var candidate: File? = File("").absoluteFile
        while (candidate != null && !File(candidate, "settings.gradle").isFile) {
            candidate = candidate.parentFile
        }
        candidate ?: throw AssertionError(
            "No settings.gradle found above ${File("").absolutePath} — cannot locate the repo root.",
        )
    }

    companion object {
        private const val OVERLAY_DISMISSER =
            "app/src/main/java/eu/darken/butler/workspace/ui/workspaces/WorkspacesScreen.kt"

        private class Exemption(val occurrences: Int, val reason: String)

        /**
         * The only places allowed to reach for the raw handler, each pinned to the number of
         * occurrences it is allowed to have (qualified reference plus bare usages).
         */
        private val ALLOWLIST = mapOf(
            // The wrapper itself — it *is* the gated BackHandler everything else is meant to use.
            "app-workspace/src/main/java/eu/darken/butler/workspace/ui/modal/WorkspaceBackHandler.kt"
                to Exemption(2, "The WorkspaceBackHandler implementation"),
            // Root-level double-press-to-exit. Registered above the workspace tree, so it stays the
            // lowest-priority handler and only runs once nothing else consumed back.
            "app/src/main/java/eu/darken/butler/main/ui/MainActivity.kt"
                to Exemption(2, "Root double-press-to-exit, intentionally lowest priority"),
            // Onboarding renders before any workspace exists — no panes, no manager overlay.
            "app/src/main/java/eu/darken/butler/main/ui/onboarding/OnboardingScreen.kt"
                to Exemption(2, "Outside the workspace tree"),
            // The manager overlay's own dismisser — the handler all of the above protects.
            OVERLAY_DISMISSER to Exemption(2, "The tab manager overlay dismisser"),
            // The guided-tour host. Mounted at window level in MainActivity, wrapping the whole
            // NavDisplay, and it registers only while a tour session is actually running — with no
            // tour up there is no handler at all, so the overlay dismisser is untouched. While a
            // tour IS up, outranking the content is the point (back steps the tour backwards), and
            // the tour cannot start behind the manager overlay: both trigger sites gate on the
            // overlay being gone, and click protection keeps the user from opening it mid-tour.
            "app-common/src/main/java/eu/darken/butler/common/compose/tour/GuidedTourHost.kt"
                to Exemption(2, "Window-level tour host, only registered while a tour runs"),
        )

        /** The declaration in its host plus the host's single call. */
        private const val OVERLAY_WRAPPER_OCCURRENCES = 2

        /**
         * Test-only infrastructure, consumed via `testImplementation` and never shipped, so its
         * `src/main` is test code by any meaning that matters here. Deliberately a named list of
         * test-support modules rather than a pattern — it must not become a way to opt production
         * code out of the ban.
         */
        private val TEST_SUPPORT_MODULES = setOf("app-common-test")

        /** Losing any of these from the build silently unguards a set of pages. */
        private val REQUIRED_WORKSPACE_MODULES = setOf(
            "app",
            "app-workspace",
            "app-workspace-apps",
            "app-workspace-bugreport",
            "app-workspace-developer",
            "app-workspace-editor",
            "app-workspace-explorer",
            "app-workspace-history",
            "app-workspace-saver",
            "app-workspace-searcher",
            "app-workspace-templates",
        )

        /** Backstop only — the per-module check above is what actually keeps the walk honest. */
        private const val MIN_SCANNED_FILES = 400

        private val INCLUDE_STATEMENT = Regex("""^\s*include\b(.*)$""", RegexOption.MULTILINE)
        private val PROJECT_PATH = Regex("""['"]:([\w\-.:]+)['"]""")
        private val QUALIFIED_REFERENCE = Regex("""androidx\.activity\.compose\.BackHandler(?!\w)""")
        private val OVERLAY_WRAPPER_REFERENCE = Regex("""(?<![\w.])ManagerOverlayBackHandler(?!\w)""")
        private val WILDCARD_IMPORT = Regex(
            """^\s*import\s+androidx\.activity\.compose\.\*""",
            RegexOption.MULTILINE,
        )
        private val BARE_REFERENCE = Regex("""(?<![\w.])BackHandler(?!\w)""")
        private const val RAW_STRING = "\"\"\""
    }
}
