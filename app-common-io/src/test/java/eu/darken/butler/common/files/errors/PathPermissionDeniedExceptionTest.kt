package eu.darken.butler.common.files.errors

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.error.Fix
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.PermissionFixResolver
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class PathPermissionDeniedExceptionTest : BaseTest() {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun build(reason: PathPermissionDeniedException.Reason) = PathPermissionDeniedException(
        path = LocalPath.build("/data/foo.txt"),
        operation = "createFile",
        reason = reason,
    )

    @Test
    fun `getLocalizedError - resolver returns Fix - fixAction non-null`() {
        val e = build(PathPermissionDeniedException.Reason.NO_MECHANISM)
        val ctx = LocalizedErrorContext(
            permissionFixResolver = PermissionFixResolver { Fix.ConfigureRootOrShizuku },
        )

        val localized = e.getLocalizedError(ctx)
        // No navController in the context, so action stays null even when Fix is present
        localized.fixAction shouldBe null
        localized.fixActionLabel?.get(app) shouldBe "Open Setup"
    }

    @Test
    fun `getLocalizedError - resolver returns null - no fixAction`() {
        val e = build(PathPermissionDeniedException.Reason.READONLY_FILESYSTEM)
        val ctx = LocalizedErrorContext(
            permissionFixResolver = PermissionFixResolver { null },
        )

        val localized = e.getLocalizedError(ctx)
        localized.fixAction shouldBe null
        localized.fixActionLabel shouldBe null
    }

    @Test
    fun `getLocalizedError - no resolver - degrades gracefully`() {
        val e = build(PathPermissionDeniedException.Reason.ACCESS_DENIED)
        val ctx = LocalizedErrorContext()

        val localized = e.getLocalizedError(ctx)
        localized.fixAction shouldBe null
        localized.fixActionLabel shouldBe null
    }

    @Test
    fun `description - NO_MECHANISM mentions no method`() {
        val e = build(PathPermissionDeniedException.Reason.NO_MECHANISM)
        val text = e.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain "No method available"
        text shouldContain "foo.txt"
    }

    @Test
    fun `description - READONLY_FILESYSTEM mentions read-only`() {
        val e = build(PathPermissionDeniedException.Reason.READONLY_FILESYSTEM)
        val text = e.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain "read-only"
    }

    @Test
    fun `description - NOT_PERMITTED mentions denial`() {
        val e = build(PathPermissionDeniedException.Reason.NOT_PERMITTED)
        val text = e.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain "denied by the system"
    }

    @Test
    fun `description - ACCESS_DENIED mentions insufficient permissions`() {
        val e = build(PathPermissionDeniedException.Reason.ACCESS_DENIED)
        val text = e.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain "insufficient permissions"
    }

    @Test
    fun `path display - root path uses full path instead of empty name`() {
        val e = PathPermissionDeniedException(
            path = LocalPath.build("/"),
            operation = "createFile",
            reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
        )

        val text = e.getLocalizedError(LocalizedErrorContext()).description.get(app)
        // Should show "/" not ""
        text shouldContain "\"/\""
    }
}
