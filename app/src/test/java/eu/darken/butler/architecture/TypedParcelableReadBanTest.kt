package eu.darken.butler.architecture

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

/**
 * Bans the typed (class-carrying) parcelable read overloads in production code, except for reads of
 * framework-owned parcelables.
 *
 * On API 33 every typed read funnels into `Parcel.readParcelableInternal(loader, clazz)`. With a
 * non-null `clazz` and a creator already in the process-static creator cache, that method evaluates
 * `creator.getClass().getEnclosingClass()` and hands the result to `clazz.isAssignableFrom(...)`.
 * kotlin-parcelize generates the creator as a class nested in its parcelable, but R8 may relocate it
 * out of that enclosing class in a minified build, so `getEnclosingClass()` returns null and the
 * check NPEs inside `Class.isInterface`. API 34 replaced that branch, and API 32 and below never had
 * it, which is what bounds the defect to API 33.
 *
 * The untyped overload passes `clazz == null` and skips the check entirely, so it is the remedy. The
 * type check was never load-bearing here: the result is cast right after the read anyway.
 *
 * Framework parcelables are exempt because framework creators live in unminified framework code and
 * stay nested inside their parcelable, so `getEnclosingClass()` is non-null for them. That is the
 * whole allowlist criterion: framework-owned parcelable, never an app-owned one.
 *
 * A read counts as typed only when it writes the class inline as `Foo::class.java`, so a `Class`
 * reaching the read in a variable goes undetected. That limitation is deliberate. The untyped
 * overloads are deprecated from API 33 on, so IDE quick-fixes and lint steer every new read site to
 * the typed overload with an inline literal, which is exactly the vector this catches. Matching
 * without requiring a literal cannot be bounded: the live production call site reads through an
 * implicit receiver inside `Parcel.use {}`, so there is no receiver to key on, and a rule keyed on
 * the bare method name alone flags every unrelated `readArray`/`readList`/`getParcelable` call.
 */
class TypedParcelableReadBanTest : BaseTest() {

    @Test
    fun `typed parcelable reads stay out of production code`() {
        val scanned = scanProductionSources()

        val violations = scanned.readsByPath
            .filterKeys { it !in ALLOWLIST }
            .keys
            .sorted()

        if (violations.isNotEmpty()) throw AssertionError(violationMessage(scanned, violations))
    }

    /**
     * File-wide exemptions would let an exempt file collect further typed reads unnoticed, and a
     * count-only exemption would keep passing if a framework read there were swapped for an
     * app-owned one. So each entry pins the number of typed reads AND every parcelable class they
     * name.
     */
    @Test
    fun `allowlisted files keep exactly their known typed reads`() {
        val scanned = scanProductionSources()

        val problems = ALLOWLIST.entries.mapNotNull { (path, entry) ->
            val file = File(repoRoot, path)
            val actual = scanned.readsByPath[path].orEmpty()
            val actualTypes = actual.flatMap { it.classArguments }.sorted()
            when {
                !file.isFile -> "$path — file no longer exists"
                actual.isEmpty() -> "$path — no typed read left, drop the entry"
                actual.size != entry.occurrences ->
                    "$path — expected ${entry.occurrences} typed read(s), found ${actual.size}: " +
                        actual.joinToString { it.render() }

                actualTypes != entry.classArguments.sorted() ->
                    "$path — expected reads of ${entry.classArguments.sorted()}, found $actualTypes"

                else -> null
            }
        }.sorted()

        if (problems.isNotEmpty()) {
            throw AssertionError(
                "The typed parcelable read allowlist no longer matches reality:\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\n\nMore than expected means a NEW typed read slipped into an exempt file — the " +
                    "exemption covers the documented ones only, not the whole file. A different class " +
                    "means the read no longer targets the framework type it was exempted for; if it " +
                    "now names an app-owned parcelable it is exactly the hazard this ban exists for. " +
                    "Fewer (or none) means the entry is stale and must be removed, otherwise it " +
                    "silently shields whatever moves into that path later.",
            )
        }
    }

    /**
     * A guard that scans nothing is worse than no guard. A global floor is not enough: `app` and
     * `app-workspace` alone clear any sensible one, so every other module could drop out of the walk
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

        val missingModules = REQUIRED_MODULES - scanned.filesByModule.keys
        if (missingModules.isNotEmpty()) {
            throw AssertionError(
                "settings.gradle no longer declares ${missingModules.sorted()}. If those modules " +
                    "were intentionally removed, drop them from REQUIRED_MODULES; otherwise their " +
                    "sources just left the scan.",
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

    @Test
    fun `the matcher reads a call spread over several lines`() {
        val source = """
            val paths = parcel.readParcelableArray(
                LocalPath::class.java.classLoader,
                LocalPath::class.java,
            )
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("readParcelableArray", listOf("LocalPath")))
    }

    @Test
    fun `the matcher reads named arguments`() {
        val source = """
            val path = parcel.readParcelable(loader = cl, clazz = LocalPath::class.java)
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("readParcelable", listOf("LocalPath")))
    }

    @Test
    fun `the matcher is not thrown off by a comma inside a nested call`() {
        val source = """
            val path = parcel.readParcelable(loaderFor(a, b), LocalPath::class.java)
            val other = parcel.readParcelable(pick(listOf(a, b), c))
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("readParcelable", listOf("LocalPath")))
    }

    @Test
    fun `the matcher is not thrown off by a comma inside a generic type argument`() {
        val source = """
            val path = parcel.readParcelable(loaderFor<Map<Key, Value>>())
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe emptyList()
    }

    @Test
    fun `the matcher ignores an unrelated call that shares a name`() {
        val source = """
            reader.readArray(count, decoder)
            helper.readList(target, source)
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe emptyList()
    }

    @Test
    fun `the matcher ignores the untyped overloads`() {
        val source = """
            val path = parcel.readParcelable<LocalPath>(LocalPath::class.java.classLoader)
            val array = parcel.readParcelableArray(LocalPath::class.java.classLoader)
            val uri = intent.getParcelableExtra<Uri>(EXTRA_INITIAL_URI)
            val list = parcel.readArrayList(cl)
            parcel.readArray(cl)
            parcel.readSparseArray(cl)
            parcel.readList(out, cl)
            parcel.readMap(out, cl)
            parcel.readHashMap(cl)
            parcel.readParcelableList(out, cl)
            parcel.readParcelableCreator(cl)
            bundle.getParcelable<LocalPath>(KEY)
            bundle.getParcelableArray(KEY)
            bundle.getParcelableArrayList<LocalPath>(KEY)
            bundle.getSparseParcelableArray<LocalPath>(KEY)
            intent.getParcelableArrayExtra(EXTRA_STREAM)
            intent.getParcelableArrayListExtra<Uri>(EXTRA_STREAM)
        """.trimIndent().withoutCommentsAndStringLiterals()

        // readParcelableArrayTyped has no line here because it has no untyped overload at all.
        source.typedParcelableReads() shouldBe emptyList()
    }

    @Test
    fun `the matcher ignores comments and string literals`() {
        val source = """
            // parcel.readParcelable(cl, LocalPath::class.java)
            /* parcel.readParcelableArray(cl, LocalPath::class.java) */
            val doc = "parcel.readParcelable(cl, LocalPath::class.java)"
            val raw = ""${'"'}parcel.readParcelable(cl, LocalPath::class.java)""${'"'}
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe emptyList()
    }

    @Test
    fun `the matcher covers the typed container reads`() {
        val source = """
            parcel.readArrayList(cl, LocalPath::class.java)
            parcel.readArray(cl, LocalPath::class.java)
            parcel.readSparseArray(cl, LocalPath::class.java)
            parcel.readList(out, cl, LocalPath::class.java)
            parcel.readMap(out, cl, String::class.java, LocalPath::class.java)
            parcel.readHashMap(cl, String::class.java, LocalPath::class.java)
            parcel.readParcelableList(out, cl, LocalPath::class.java)
            parcel.readParcelableCreator(cl, LocalPath::class.java)
            ParcelCompat.readParcelableArrayTyped(parcel, cl, LocalPath::class.java)
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads().map { it.method } shouldBe listOf(
            "readArrayList",
            "readArray",
            "readSparseArray",
            "readList",
            "readMap",
            "readHashMap",
            "readParcelableList",
            "readParcelableCreator",
            "readParcelableArrayTyped",
        )
    }

    @Test
    fun `the matcher covers the Intent and Bundle forms`() {
        val source = """
            intent.getParcelableExtra(EXTRA_STREAM, Uri::class.java)
            bundle.getParcelable(KEY, LocalPath::class.java)
            IntentCompat.getParcelableExtra(intent, EXTRA_INTENT, Intent::class.java)
            BundleCompat.getParcelable(bundle, KEY, LocalPath::class.java)
            bundle.getParcelableArray(KEY, LocalPath::class.java)
            bundle.getParcelableArrayList(KEY, LocalPath::class.java)
            bundle.getSparseParcelableArray(KEY, LocalPath::class.java)
            intent.getParcelableArrayExtra(EXTRA_STREAM, Uri::class.java)
            intent.getParcelableArrayListExtra(EXTRA_STREAM, Uri::class.java)
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(
            Read("getParcelableExtra", listOf("Uri")),
            Read("getParcelable", listOf("LocalPath")),
            Read("getParcelableExtra", listOf("Intent")),
            Read("getParcelable", listOf("LocalPath")),
            Read("getParcelableArray", listOf("LocalPath")),
            Read("getParcelableArrayList", listOf("LocalPath")),
            Read("getSparseParcelableArray", listOf("LocalPath")),
            Read("getParcelableArrayExtra", listOf("Uri")),
            Read("getParcelableArrayListExtra", listOf("Uri")),
        )
    }

    @Test
    fun `the matcher reads a string interpolation body`() {
        val source = """
            log(tag) { "paths=${'$'}{items.map { bundle.getParcelable(KEY, LocalPath::class.java) }}" }
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("getParcelable", listOf("LocalPath")))
    }

    @Test
    fun `the matcher reads a raw string interpolation body`() {
        val source = """
            val text = ""${'"'}stream=${'$'}{intent.getParcelableExtra(EXTRA_STREAM, Uri::class.java)}""${'"'}
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("getParcelableExtra", listOf("Uri")))
    }

    @Test
    fun `the matcher records every class a typed read names`() {
        val source = """
            parcel.readMap(out, cl, String::class.java, LocalPath::class.java)
        """.trimIndent().withoutCommentsAndStringLiterals()

        source.typedParcelableReads() shouldBe listOf(Read("readMap", listOf("String", "LocalPath")))
    }

    private fun violationMessage(scanned: Scan, violations: List<String>): String = buildString {
        appendLine("Typed (class-carrying) parcelable reads found outside the allowlist:")
        appendLine()
        violations.forEach { path ->
            appendLine("  $path")
            scanned.readsByPath.getValue(path).forEach { read ->
                appendLine("    ${read.render()}")
            }
        }
        appendLine()
        appendLine("On API 33 every typed read funnels into readParcelableInternal(loader, clazz).")
        appendLine("With a creator already in the process-static creator cache, that path evaluates")
        appendLine("  creator.getClass().getEnclosingClass()")
        appendLine("and passes it to clazz.isAssignableFrom(...). R8 can relocate a kotlin-parcelize")
        appendLine("generated Parcelable\$Creator out of the class it is nested in, so in a minified")
        appendLine("build getEnclosingClass() returns null and the check NPEs inside")
        appendLine("Class.isInterface. Two-or-more element reads hit it on the second element even in")
        appendLine("a cold process.")
        appendLine()
        appendLine("Use the untyped overload instead — drop the Class argument and cast the result,")
        appendLine("which is what this code already does on API 32 and below. It passes clazz == null")
        appendLine("and skips the enclosing-class check.")
        appendLine()
        appendLine("Only reads of FRAMEWORK-owned parcelables (PackageInfo, Uri, Intent,")
        appendLine("StorageVolume, …) qualify for ALLOWLIST in this test: their creators are not")
        append("minified and stay nested inside their parcelable. App-owned ones never qualify.")
    }

    /** One typed read: the method that was called and every class argument it passes. */
    private data class Read(val method: String, val classArguments: List<String>) {
        fun render(): String = "$method(…, " + classArguments.joinToString { "$it::class.java" } + ")"
    }

    private class Scan(
        val filesByModule: Map<String, Int>,
        val readsByPath: Map<String, List<Read>>,
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
        val readsByPath = mutableMapOf<String, List<Read>>()

        modules.forEach { module ->
            val src = File(repoRoot, "${module.replace(':', '/')}/src")
            if (!File(src, "main").isDirectory) {
                throw AssertionError("$module has no src/main — the source layout changed.")
            }
            // Every production source set (main plus flavor/build-type ones), never a test one:
            // tests may use the typed overload to pin what the framework does with it.
            val files = src.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
                .flatMap { sourceSet ->
                    sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }

            filesByModule[module] = files.size
            files.forEach { file ->
                val reads = file.readText().withoutCommentsAndStringLiterals().typedParcelableReads()
                if (reads.isNotEmpty()) readsByPath[file.relativePath()] = reads
            }
        }

        return Scan(filesByModule, readsByPath)
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
     * Finds every call that hands a read an inline `Foo::class.java`, and names every class it
     * passes. Arguments are split at the top level, so a comma inside a nested call does not break
     * the walk, and a named argument is matched by the class literal rather than its position.
     */
    private fun String.typedParcelableReads(): List<Read> = READ_CALL.findAll(this).mapNotNull { match ->
        val open = match.range.last
        val close = closingParen(open) ?: return@mapNotNull null
        val classArguments = topLevelArguments(substring(open + 1, close)).mapNotNull { argument ->
            CLASS_LITERAL.find(argument.filterNot { it.isWhitespace() })?.groupValues?.get(1)
        }
        if (classArguments.isEmpty()) return@mapNotNull null
        Read(match.groupValues[1], classArguments)
    }.toList()

    /** Index of the `)` closing the `(` at [open], or null if the call is unbalanced. */
    private fun String.closingParen(open: Int): Int? {
        var depth = 0
        var i = open
        while (i < length) {
            when (this[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** Splits an argument list on its top-level commas, dropping a trailing comma's empty tail. */
    private fun topLevelArguments(arguments: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        arguments.forEach { char ->
            when {
                char == '(' || char == '[' || char == '{' -> {
                    depth++
                    current.append(char)
                }

                char == ')' || char == ']' || char == '}' -> {
                    depth--
                    current.append(char)
                }

                char == ',' && depth == 0 -> {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
        }
        out += current.toString()
        return out.filter { it.isNotBlank() }
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

                startsWith(RAW_STRING, i) -> i = blankLiteral(i, RAW_STRING, escapes = false, out = out)

                char == '"' || char == '\'' ->
                    i = blankLiteral(i, char.toString(), escapes = true, out = out)

                else -> {
                    out.append(char)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Blanks the literal opening at [start] and returns the index just past its closing [terminator].
     *
     * A `${…}` body is code the compiler runs, not text, so it is emitted verbatim while the literal
     * around it is blanked — `"path=${bundle.getParcelable(KEY, LocalPath::class.java)}"` is a typed
     * read like any other. Braces are counted so a lambda inside the body does not end it early, and
     * a bare `$name` carries no call and is blanked with the rest.
     */
    private fun String.blankLiteral(start: Int, terminator: String, escapes: Boolean, out: StringBuilder): Int {
        out.append(' ')
        var i = start + terminator.length
        while (i < length && !startsWith(terminator, i)) {
            when {
                escapes && this[i] == '\\' -> i += 2

                startsWith(INTERPOLATION, i) -> {
                    i += INTERPOLATION.length
                    var depth = 1
                    out.append(' ')
                    while (i < length && depth > 0) {
                        when (this[i]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth > 0) out.append(this[i])
                        i++
                    }
                    out.append(' ')
                }

                else -> i++
            }
        }
        return minOf(i + terminator.length, length)
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

        private class Exemption(
            val occurrences: Int,
            val classArguments: List<String>,
            val reason: String,
        )

        /**
         * The only typed reads allowed, all of framework parcelables. Keyed by file, so
         * `StorageVolumeX.kt`'s two reads share one entry; it also holds an untyped read of each
         * shape, which makes its pinned count a check that the matcher does not over-match.
         */
        private val ALLOWLIST = mapOf(
            "app-common-io/src/main/java/eu/darken/butler/common/pkgs/pkgops/ipc/PackageInfoPayload.kt"
                to Exemption(1, listOf("PackageInfo"), "Framework PackageInfo"),
            "app-common-io/src/main/java/eu/darken/butler/common/storage/StorageVolumeX.kt"
                to Exemption(2, listOf("Uri", "Any"), "Framework Uri, and Any that is only ever a StorageVolume"),
            "app-common-io/src/main/java/eu/darken/butler/common/pkgs/installer/AppInstallStatusReceiver.kt"
                to Exemption(1, listOf("Intent"), "Framework Intent"),
        )

        /**
         * Test-only infrastructure, consumed via `testImplementation` and never shipped, so its
         * `src/main` is test code by any meaning that matters here. Deliberately a named list of
         * test-support modules rather than a pattern — it must not become a way to opt production
         * code out of the ban.
         */
        private val TEST_SUPPORT_MODULES = setOf("app-common-test")

        /** Losing any of these from the build silently unguards a set of sources. */
        private val REQUIRED_MODULES = setOf(
            "app",
            "app-common",
            "app-common-adb",
            "app-common-io",
            "app-common-pkgs",
            "app-common-root",
            "app-common-shell",
            "app-provider-documents",
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
            "app-workspace-viewer",
        )

        /** Backstop only — the per-module check above is what actually keeps the walk honest. */
        private const val MIN_SCANNED_FILES = 400

        private val INCLUDE_STATEMENT = Regex("""^\s*include\b(.*)$""", RegexOption.MULTILINE)
        private val PROJECT_PATH = Regex("""['"]:([\w\-.:]+)['"]""")

        /**
         * The read plus an optional explicit type argument, up to its opening parenthesis. A longer
         * name is listed before any shorter one that prefixes it only for readability — the trailing
         * `(?!\w)` is what keeps the shorter name from claiming the longer one.
         */
        private val READ_CALL = Regex(
            """(?<!\w)(readParcelableArrayTyped|readParcelableArray|readParcelableList""" +
                """|readParcelableCreator|readParcelable|readArrayList|readArray|readSparseArray""" +
                """|readList|readMap|readHashMap|getParcelableArrayListExtra|getParcelableArrayList""" +
                """|getParcelableArrayExtra|getParcelableArray|getSparseParcelableArray""" +
                """|getParcelableExtra|getParcelable)(?!\w)""" +
                """\s*(?:<[^()\n]*>)?\s*\(""",
        )
        private val CLASS_LITERAL = Regex("""^(?:\w+=)?([\w.]+)::class\.java$""")
        private const val RAW_STRING = "\"\"\""
        private const val INTERPOLATION = "\${"
    }
}
