package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class LocalPathDeleteDefaultsTest : BaseTest() {

    @BeforeEach
    fun setup() {
        mockkStatic("eu.darken.butler.common.files.local.LocalPathDeleteKt")
    }

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `single file delete uses default parameters`() = runTest {
        // Given
        val mockFile = mockk<File>()
        val testPath = LocalPath.build(mockFile)
        val mockResult = mockk<DeleteAction.State.Result<LocalPath, LocalPathLookup>>()

        // Mock the collection delete function to return a result
        coEvery {
            any<Collection<LocalPath>>().delete(
                recursive = any(),
                ignoreMissing = any(),
                onProgress = any(),
                onIssue = any()
            )
        } returns mockResult

        // When - call single file delete without parameters
        val result = testPath.delete()

        // Then - verify it called collection delete with correct defaults
        coVerify(exactly = 1) {
            setOf(testPath).delete(
                recursive = true,
                ignoreMissing = true,
                onProgress = null,
                onIssue = null
            )
        }
        result shouldBe mockResult
    }

    @Test
    fun `single file delete with custom parameters passes them through`() = runTest {
        // Given
        val mockFile = mockk<File>()
        val testPath = LocalPath.build(mockFile)
        val mockResult = mockk<DeleteAction.State.Result<LocalPath, LocalPathLookup>>()
        val mockProgress: (suspend (DeleteAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit) = mockk()
        val mockIssueHandler: (suspend (PathActionIssue) -> PathActionIssue.Resolution) = mockk()

        coEvery {
            any<Collection<LocalPath>>().delete(
                recursive = any(),
                ignoreMissing = any(),
                onProgress = any(),
                onIssue = any()
            )
        } returns mockResult

        // When - call single file delete with custom parameters
        val result = testPath.delete(
            recursive = false,
            ignoreMissing = false,
            onProgress = mockProgress,
            onIssue = mockIssueHandler
        )

        // Then - verify it passed through the custom parameters
        coVerify(exactly = 1) {
            setOf(testPath).delete(
                recursive = false,
                ignoreMissing = false,
                onProgress = mockProgress,
                onIssue = mockIssueHandler
            )
        }
        result shouldBe mockResult
    }

    @Test
    fun `collection delete function signature has correct defaults`() = runTest {
        // Given
        val mockFile1 = mockk<File>()
        val mockFile2 = mockk<File>()
        val testPaths = listOf(LocalPath.build(mockFile1), LocalPath.build(mockFile2))
        val mockResult = mockk<DeleteAction.State.Result<LocalPath, LocalPathLookup>>()

        // Mock the actual collection delete implementation
        coEvery {
            any<Collection<LocalPath>>().delete(
                recursive = any(),
                ignoreMissing = any(),
                onProgress = any(),
                onIssue = any()
            )
        } returns mockResult

        // When - call collection delete without parameters (relying on defaults in function signature)
        val result = testPaths.delete()

        // Then - verify defaults are applied correctly
        coVerify(exactly = 1) {
            testPaths.delete(
                recursive = true,
                ignoreMissing = true,
                onProgress = null,
                onIssue = null
            )
        }
        result shouldBe mockResult
    }

    @Test
    fun `verify parameter defaults match between single and collection functions`() = runTest {
        // This test ensures that if we change defaults in one place,
        // we remember to change them in both functions

        val mockFile = mockk<File>()
        val testPath = LocalPath.build(mockFile)
        val mockResult = mockk<DeleteAction.State.Result<LocalPath, LocalPathLookup>>()

        coEvery {
            any<Collection<LocalPath>>().delete(
                recursive = any(),
                ignoreMissing = any(),
                onProgress = any(),
                onIssue = any()
            )
        } returns mockResult

        // When - call both functions without parameters
        testPath.delete()
        listOf(testPath).delete()

        // Then - verify both use the same defaults
        coVerify(exactly = 2) {
            any<Collection<LocalPath>>().delete(
                recursive = true,
                ignoreMissing = true,
                onProgress = null,
                onIssue = null
            )
        }
    }

    @Test
    fun `single file delete converts to set correctly`() = runTest {
        // Given
        val mockFile = mockk<File>()
        val testPath = LocalPath.build(mockFile)
        val mockResult = mockk<DeleteAction.State.Result<LocalPath, LocalPathLookup>>()

        coEvery {
            setOf(testPath).delete(
                recursive = any(),
                ignoreMissing = any(),
                onProgress = any(),
                onIssue = any()
            )
        } returns mockResult

        // When
        val result = testPath.delete()

        // Then - verify it creates a set with the single path
        coVerify(exactly = 1) {
            setOf(testPath).delete(
                recursive = true,
                ignoreMissing = true,
                onProgress = null,
                onIssue = null
            )
        }
        result shouldBe mockResult
    }
}