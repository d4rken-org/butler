package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.file.AccessDeniedException
import kotlin.time.Instant

/**
 * Tests for TransferErrorHandler - unified error handling for path operations.
 *
 * Verifies that the handler correctly:
 * - Categorizes errors (permission vs unknown)
 * - Applies "apply to all" flags correctly
 * - Invokes callbacks (onSkip, onRetry)
 * - Re-throws errors when no issue handler is configured
 * - Handles both transfer errors and scan errors
 */
class TransferErrorHandlerTest : BaseTest() {

    private val testPath = LocalPath.build("/test/file.txt")
    private val testLookup = LocalPathLookup(
        lookedUp = testPath,
        fileType = FileType.FILE,
        size = 100,
        modifiedAt = Instant.fromEpochMilliseconds(0),
        target = null
    )

    // ============ PERMISSION ERROR HANDLING ============

    @Test
    fun `permission error with skipAllPermission flag should skip without prompting`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        // Setup issueResolver with applyToAll=true to enable skipAllPermission flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one permission issue
        issueResolver.resolveIssue(
            PathActionIssue.InsufficientPermission(
                destinationPath = testPath,
                exception = AccessDeniedException(testPath.path)
            )
        )

        var skippedCalled = false
        var retryCalled = false

        // When
        errorHandler.handleError(
            error = AccessDeniedException(testPath.path),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skippedCalled = true },
            onRetry = { retryCalled = true },
            canRetry = true,
            onIssue = null,
            tag = "TEST"
        )

        // Then
        skippedCalled shouldBe true
        retryCalled shouldBe false
        progressTracker.itemsProcessed shouldBe 1
    }

    @Test
    fun `permission error without handler should throw original exception`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        val issueResolver = PathOperationIssueResolver(onIssue = null)
        val originalError = AccessDeniedException(testPath.path)

        // When/Then
        shouldThrow<AccessDeniedException> {
            errorHandler.handleError(
                error = originalError,
                sourceLookup = testLookup,
                issueResolver = issueResolver,
                progressTracker = progressTracker,
                onSkip = { },
                onRetry = { },
                canRetry = false,
                onIssue = null,
                tag = "TEST"
            )
        }
    }

    @Test
    fun `permission error with handler should prompt user`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        var resolverCalled = false
        val issueResolver = PathOperationIssueResolver { issue ->
            resolverCalled = true
            (issue is PathActionIssue.InsufficientPermission) shouldBe true
            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = false)
        }

        var skippedCalled = false

        // When
        errorHandler.handleError(
            error = SecurityException("Access denied"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skippedCalled = true },
            onRetry = { },
            canRetry = false,
            onIssue = { issue -> issueResolver.resolveIssue(issue) },
            tag = "TEST"
        )

        // Then
        resolverCalled shouldBe true
        skippedCalled shouldBe true
    }

    // ============ UNKNOWN ERROR HANDLING ============

    @Test
    fun `unknown error with skipAllUnknown flag should skip without prompting`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        // Setup issueResolver with applyToAll=true to enable skipAllUnknown flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one unknown error issue
        issueResolver.resolveIssue(
            PathActionIssue.UnknownError(destinationPath = testPath, exception = RuntimeException("Error"))
        )

        var skippedCalled = false

        // When
        errorHandler.handleError(
            error = RuntimeException("Unknown error"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skippedCalled = true },
            onRetry = { },
            canRetry = true,
            onIssue = null,
            tag = "TEST"
        )

        // Then
        skippedCalled shouldBe true
        progressTracker.itemsProcessed shouldBe 1
    }

    @Test
    fun `unknown error with retry resolution should invoke onRetry callback`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Retry
        }

        var retryCalled = false
        var skippedCalled = false

        // When
        errorHandler.handleError(
            error = RuntimeException("Temp error"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skippedCalled = true },
            onRetry = { retryCalled = true },
            canRetry = true,
            onIssue = { issue -> issueResolver.resolveIssue(issue) },
            tag = "TEST"
        )

        // Then
        retryCalled shouldBe true
        skippedCalled shouldBe false
        progressTracker.itemsProcessed shouldBe 0  // Not completed during retry
    }

    @Test
    fun `unknown error retry without onRetry callback should skip`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val progressTracker = PathOperationProgressTracker()
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Retry
        }

        var skippedCalled = false

        // When - onRetry is null
        errorHandler.handleError(
            error = RuntimeException("Error"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            onSkip = { skippedCalled = true },
            onRetry = null,
            canRetry = false,
            onIssue = { issue -> issueResolver.resolveIssue(issue) },
            tag = "TEST"
        )

        // Then - should fall back to skip
        skippedCalled shouldBe true
        progressTracker.itemsProcessed shouldBe 1
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `scan error with skipAllPermission should skip without prompting`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        // Setup issueResolver with applyToAll=true to enable skipAllPermission flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one permission issue
        issueResolver.resolveIssue(
            PathActionIssue.InsufficientPermission(
                destinationPath = testPath,
                exception = AccessDeniedException(testPath.path)
            )
        )

        var skippedCalled = false
        var retryCalled = false

        // When
        errorHandler.handleScanError(
            error = AccessDeniedException(testPath.path),
            lookup = testLookup,
            issueResolver = issueResolver,
            onSkip = { skippedCalled = true },
            onRetry = { retryCalled = true },
            onIssue = null,
            tag = "TEST"
        )

        // Then
        skippedCalled shouldBe true
        retryCalled shouldBe false
    }

    @Test
    fun `scan error with skipAllUnknown should skip without prompting`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        // Setup issueResolver with applyToAll=true to enable skipAllUnknown flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one unknown error issue
        issueResolver.resolveIssue(
            PathActionIssue.UnknownError(destinationPath = testPath, exception = RuntimeException("Error"))
        )

        var skippedCalled = false

        // When
        errorHandler.handleScanError(
            error = RuntimeException("Scan error"),
            lookup = testLookup,
            issueResolver = issueResolver,
            onSkip = { skippedCalled = true },
            onRetry = { },
            onIssue = null,
            tag = "TEST"
        )

        // Then
        skippedCalled shouldBe true
    }

    @Test
    fun `scan error with retry resolution should invoke onRetry`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Retry
        }

        var retryCalled = false
        var skippedCalled = false

        // When
        errorHandler.handleScanError(
            error = RuntimeException("Scan failed"),
            lookup = testLookup,
            issueResolver = issueResolver,
            onSkip = { skippedCalled = true },
            onRetry = { retryCalled = true },
            onIssue = { issue -> issueResolver.resolveIssue(issue) },
            tag = "TEST"
        )

        // Then
        retryCalled shouldBe true
        skippedCalled shouldBe false
    }

    @Test
    fun `scan error without handler should throw original exception`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        val issueResolver = PathOperationIssueResolver(onIssue = null)
        val originalError = RuntimeException("Scan failed")

        // When/Then
        shouldThrow<RuntimeException> {
            errorHandler.handleScanError(
                error = originalError,
                lookup = testLookup,
                issueResolver = issueResolver,
                onSkip = { },
                onRetry = { },
                onIssue = null,
                tag = "TEST"
            )
        }
    }

    @Test
    fun `scan error always creates UnknownError issue for retry support`() = runTest {
        // Given - even permission errors use UnknownError for scan operations
        val errorHandler = TransferErrorHandler()
        var issueType: PathActionIssue? = null
        val issueResolver = PathOperationIssueResolver { issue ->
            issueType = issue
            PathActionIssue.UnknownError.Resolution.Skip(applyToAll = false)
        }

        // When - AccessDeniedException during scan
        errorHandler.handleScanError(
            error = AccessDeniedException(testPath.path),
            lookup = testLookup,
            issueResolver = issueResolver,
            onSkip = { },
            onRetry = { },
            onIssue = { issue -> issueResolver.resolveIssue(issue) },
            tag = "TEST"
        )

        // Then - should be UnknownError (not InsufficientPermission)
        (issueType is PathActionIssue.UnknownError) shouldBe true
    }

    // ============ ERROR CATEGORIZATION ============

    @Test
    fun `SecurityException is categorized as permission error`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        // Setup issueResolver with applyToAll=true to enable skipAllPermission flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one permission issue
        issueResolver.resolveIssue(
            PathActionIssue.InsufficientPermission(
                destinationPath = testPath,
                exception = SecurityException("Access denied")
            )
        )

        var skippedCalled = false

        // When - SecurityException
        errorHandler.handleError(
            error = SecurityException("Access denied"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = PathOperationProgressTracker(),
            onSkip = { skippedCalled = true },
            onRetry = { },
            canRetry = false,
            onIssue = null,
            tag = "TEST"
        )

        // Then - should be handled as permission error
        skippedCalled shouldBe true
    }

    @Test
    fun `AccessDeniedException is categorized as permission error`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        // Setup issueResolver with applyToAll=true to enable skipAllPermission flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one permission issue
        issueResolver.resolveIssue(
            PathActionIssue.InsufficientPermission(
                destinationPath = testPath,
                exception = AccessDeniedException(testPath.path)
            )
        )

        var skippedCalled = false

        // When - AccessDeniedException
        errorHandler.handleError(
            error = AccessDeniedException(testPath.path),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = PathOperationProgressTracker(),
            onSkip = { skippedCalled = true },
            onRetry = { },
            canRetry = false,
            onIssue = null,
            tag = "TEST"
        )

        // Then - should be handled as permission error
        skippedCalled shouldBe true
    }

    @Test
    fun `RuntimeException is categorized as unknown error`() = runTest {
        // Given
        val errorHandler = TransferErrorHandler()
        // Setup issueResolver with applyToAll=true to enable skipAllUnknown flag
        val issueResolver = PathOperationIssueResolver { _ ->
            PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
        }
        // Trigger the flag by resolving one unknown error issue
        issueResolver.resolveIssue(
            PathActionIssue.UnknownError(destinationPath = testPath, exception = RuntimeException("Error"))
        )

        var skippedCalled = false

        // When - RuntimeException
        errorHandler.handleError(
            error = RuntimeException("Something went wrong"),
            sourceLookup = testLookup,
            issueResolver = issueResolver,
            progressTracker = PathOperationProgressTracker(),
            onSkip = { skippedCalled = true },
            onRetry = { },
            canRetry = true,
            onIssue = null,
            tag = "TEST"
        )

        // Then - should be handled as unknown error
        skippedCalled shouldBe true
    }
}
