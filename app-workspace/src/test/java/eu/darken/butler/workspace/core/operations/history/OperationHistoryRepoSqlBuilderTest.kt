package eu.darken.butler.workspace.core.operations.history

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Pure-JVM unit tests for the dynamic SQL builder + LIKE-escape + path-scope normalization used
 * by [OperationHistoryRepo]. No Room / Robolectric — exercises the static helpers exposed via the
 * companion object. Actual matching behavior is covered by [OperationHistoryScopeQueryTest].
 *
 * Behaviors covered:
 *  - Bind-arg layout: outcomes, kinds, then 2 placeholders per scope, then limit.
 *  - Multi-scope OR joining of the EXISTS predicate against the scope index.
 *  - The exact placeholder gets the RAW scope, the LIKE placeholder the escaped pattern.
 *  - LIKE-escape: `%`, `_`, `\` in user paths are not treated as wildcards.
 *  - Trailing-slash + blank input normalization.
 */
class OperationHistoryRepoSqlBuilderTest {

    /** Records bind values in placeholder order. */
    private fun bindArgsOf(query: SimpleSQLiteQuery): List<Any?> {
        val recorded = mutableListOf<Any?>()
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) {
                recorded += null
            }

            override fun bindLong(index: Int, value: Long) {
                recorded += value
            }

            override fun bindDouble(index: Int, value: Double) {
                recorded += value
            }

            override fun bindString(index: Int, value: String) {
                recorded += value
            }

            override fun bindBlob(index: Int, value: ByteArray) {
                recorded += value
            }

            override fun clearBindings() {
                recorded.clear()
            }

            override fun close() = Unit
        })
        return recorded
    }

    // ─── escape ────────────────────────────────────────────────────────────────────

    @Test
    fun `escape leaves plain paths untouched`() {
        OperationHistoryRepo.escapeLikePatternStatic("/sdcard/DCIM") shouldBe "/sdcard/DCIM"
    }

    @Test
    fun `escape doubles backslash first then escapes percent and underscore`() {
        // backslash escaped first so later percent/underscore escapes don't get re-escaped
        OperationHistoryRepo.escapeLikePatternStatic("a\\b%c_d") shouldBe "a\\\\b\\%c\\_d"
    }

    // ─── descendant pattern ────────────────────────────────────────────────────────

    @Test
    fun `descendant pattern appends a single slash wildcard`() {
        OperationHistoryRepo.descendantPatternStatic("/sdcard/DCIM") shouldBe "/sdcard/DCIM/%"
    }

    @Test
    fun `descendant pattern for the root scope is not a double slash`() {
        OperationHistoryRepo.descendantPatternStatic("/") shouldBe "/%"
    }

    @Test
    fun `descendant pattern escapes wildcards in the scope`() {
        OperationHistoryRepo.descendantPatternStatic("/foo%bar") shouldBe "/foo\\%bar/%"
    }

    // ─── SQL builder ───────────────────────────────────────────────────────────────

    @Test
    fun `single-scope query has correct placeholder count and shape`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("FAILED"),
            kinds = listOf("DELETE"),
            pathScopes = listOf("/sdcard/DCIM"),
            limit = 100,
        )
        // 1 outcome + 1 kind + 2 scope-placeholders + 1 limit = 5 args
        q.argCount shouldBe 5

        val sql = q.sql
        sql shouldContain "SELECT id FROM operation_history"
        sql shouldContain "WHERE outcome IN (?)"
        sql shouldContain "AND kind IN (?)"
        sql shouldContain "ORDER BY completedAt DESC"
        sql shouldContain "LIMIT ?"

        // The EXISTS sub-query reads the scope index, exact + descendant
        sql shouldContain "FROM operation_history_scope s"
        sql shouldContain "s.path = ?"
        sql shouldContain "s.path LIKE ? ESCAPE '\\'"
        // Move sources live in the scope index in their own right
        sql shouldNotContain "previousPath"
        // Concatenating the wildcard in SQL breaks the root scope
        sql shouldNotContain "|| '/%'"
    }

    @Test
    fun `multi-scope query OR-joins predicates and emits 2 placeholders per scope`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED", "FAILED"),
            kinds = listOf("COPY", "DELETE"),
            pathScopes = listOf("/sdcard/DCIM", "/sdcard/Documents", "/sdcard/Music"),
            limit = 50,
        )
        // 2 outcomes + 2 kinds + 3*2 scope placeholders + 1 limit = 11 args
        q.argCount shouldBe 11

        val sql = q.sql
        sql shouldContain "WHERE outcome IN (?,?)"
        sql shouldContain "AND kind IN (?,?)"

        // 3 scopes joined by " OR ": each scope opens with "(s.path "; counting how many of those
        // exist in the SQL gives us the number of scope predicates.
        val scopeOpenings = sql.split("(s.path = ?").size - 1
        scopeOpenings shouldBe 3
    }

    @Test
    fun `the exact placeholder gets the raw scope and the LIKE placeholder the escaped pattern`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED"),
            kinds = listOf("COPY"),
            pathScopes = listOf("/foo%bar"),
            limit = 10,
        )

        // Escaping the value bound to `=` would make a scope with a wildcard char never match itself.
        // SimpleSQLiteQuery binds Int args via bindLong, hence 10L.
        bindArgsOf(q) shouldBe listOf("COMPLETED", "COPY", "/foo%bar", "/foo\\%bar/%", 10L)
    }

    @Test
    fun `the root scope binds a single slash wildcard`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED"),
            kinds = listOf("COPY"),
            pathScopes = listOf("/"),
            limit = 10,
        )

        bindArgsOf(q) shouldBe listOf("COMPLETED", "COPY", "/", "/%", 10L)
    }

    @Test
    fun `arg count is invariant to scope length`() {
        val short = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED"),
            kinds = listOf("COPY"),
            pathScopes = listOf("/A"),
            limit = 10,
        )
        val long = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED"),
            kinds = listOf("COPY"),
            pathScopes = listOf("/very/deep/nested/path/" + "x".repeat(200)),
            limit = 10,
        )
        short.argCount shouldBe long.argCount
    }

    @Test
    fun `SQL preserves ESCAPE clause for descendant matching`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED"),
            kinds = listOf("COPY"),
            pathScopes = listOf("/foo%bar"),
            limit = 10,
        )
        // The ESCAPE '\' is in the SQL itself (so the engine knows how to interpret \% / \_).
        // The actual wildcards are escaped at bind time via escapeLikePatternStatic, verified above.
        q.sql shouldContain "ESCAPE '\\'"
    }

    // ─── normalizePathScope ───────────────────────────────────────────────────────

    @Test
    fun `normalize trims whitespace`() {
        OperationHistoryRepo.normalizePathScope("  /sdcard/DCIM  ") shouldBe "/sdcard/DCIM"
    }

    @Test
    fun `normalize drops trailing slashes`() {
        OperationHistoryRepo.normalizePathScope("/sdcard/DCIM/") shouldBe "/sdcard/DCIM"
        OperationHistoryRepo.normalizePathScope("/sdcard/DCIM///") shouldBe "/sdcard/DCIM"
    }

    @Test
    fun `normalize preserves root slash`() {
        OperationHistoryRepo.normalizePathScope("/") shouldBe "/"
    }

    @Test
    fun `normalize returns null for blank input`() {
        OperationHistoryRepo.normalizePathScope("") shouldBe null
        OperationHistoryRepo.normalizePathScope("   ") shouldBe null
    }
}
