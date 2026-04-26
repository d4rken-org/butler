package eu.darken.butler.workspace.core.operations.history

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Pure-JVM unit tests for the dynamic SQL builder + LIKE-escape + path-scope normalization used
 * by [OperationHistoryRepo]. No Room / Robolectric — exercises the static helpers exposed via the
 * companion object.
 *
 * Behaviors covered (per Codex review of multi-path filter design):
 *  - Bind-arg layout: outcomes, kinds, then 4 placeholders per scope, then limit.
 *  - Multi-scope OR joining of the EXISTS predicate.
 *  - `previousPath` matched alongside `path` so move/rename out of a scope still appears.
 *  - LIKE-escape: `%`, `_`, `\` in user paths are not treated as wildcards.
 *  - Trailing-slash + blank input normalization.
 */
class OperationHistoryRepoSqlBuilderTest {

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

    // ─── SQL builder ───────────────────────────────────────────────────────────────

    @Test
    fun `single-scope query has correct placeholder count and shape`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("FAILED"),
            kinds = listOf("DELETE"),
            pathScopes = listOf("/sdcard/DCIM"),
            limit = 100,
        )
        // 1 outcome + 1 kind + 4 scope-placeholders + 1 limit = 7 args
        q.argCount shouldBe 7

        val sql = q.sql
        sql shouldContain "SELECT id FROM operation_history"
        sql shouldContain "WHERE outcome IN (?)"
        sql shouldContain "AND kind IN (?)"
        sql shouldContain "ORDER BY completedAt DESC"
        sql shouldContain "LIMIT ?"

        // EXISTS sub-query covers both path and previousPath, exact + descendant
        sql shouldContain "p.path = ?"
        sql shouldContain "p.path LIKE ? || '/%' ESCAPE '\\'"
        sql shouldContain "p.previousPath = ?"
        sql shouldContain "p.previousPath LIKE ? || '/%' ESCAPE '\\'"
    }

    @Test
    fun `multi-scope query OR-joins predicates and emits 4 placeholders per scope`() {
        val q = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = listOf("COMPLETED", "FAILED"),
            kinds = listOf("COPY", "DELETE"),
            pathScopes = listOf("/sdcard/DCIM", "/sdcard/Documents", "/sdcard/Music"),
            limit = 50,
        )
        // 2 outcomes + 2 kinds + 3*4 scope placeholders + 1 limit = 17 args
        q.argCount shouldBe 17

        val sql = q.sql
        sql shouldContain "WHERE outcome IN (?,?)"
        sql shouldContain "AND kind IN (?,?)"

        // 3 scopes joined by " OR ": each scope opens with "(p.path "; counting how many of those
        // exist in the SQL gives us the number of scope predicates.
        val scopeOpenings = sql.split("(p.path = ?").size - 1
        scopeOpenings shouldBe 3
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
