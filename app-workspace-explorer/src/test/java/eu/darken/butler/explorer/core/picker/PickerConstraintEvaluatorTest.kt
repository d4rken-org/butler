package eu.darken.butler.workspace.contracts.explorer

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PickerConstraintEvaluatorTest : BaseTest() {

    // ═══════════════════════════════════════════════════════════════
    // Test Fixtures
    // ═══════════════════════════════════════════════════════════════

    private fun mockDirectory(childCount: Int? = null, canWrite: Boolean? = null): ExplorerItem.Directory {
        val lookup = mockk<APathLookup<*>> {
            every { size } returns 4096L
        }
        return mockk<ExplorerItem.RegularDirectory> {
            every { this@mockk.lookup } returns lookup
            every { this@mockk.childCount } returns childCount
            every { this@mockk.canWrite } returns canWrite
        }
    }

    private fun mockFile(mimeType: String = "application/octet-stream", size: Long? = 1024L): ExplorerItem.File {
        val lookup = mockk<APathLookup<*>> {
            every { this@mockk.size } returns size
        }
        return mockk<ExplorerItem.RegularFile> {
            every { this@mockk.lookup } returns lookup
            every { this@mockk.mimeType } returns MimeInfo(mimeType)
        }
    }

    private fun mockStorage(canWrite: Boolean? = null): ExplorerItem.Storage {
        return mockk<ExplorerItem.Storage.Local> {
            every { displayName } returns "Internal Storage".toCaString()
            every { target } returns mockk<ExplorerNavigation.Target.Directory>()
            every { this@mockk.canWrite } returns canWrite
        }
    }

    private fun mockShortcut(shortcutId: String): ExplorerItem.Shortcut {
        return mockk<ExplorerItem.Shortcut> {
            every { this@mockk.shortcutId } returns shortcutId
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Type Constraints
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class TypeConstraints {

        @Test
        fun `IsDirectory matches Directory items`() {
            val directory = mockDirectory()
            val file = mockFile()

            PickerConstraint.IsDirectory.matches(directory) shouldBe true
            PickerConstraint.IsDirectory.matches(file) shouldBe false
        }

        @Test
        fun `IsFile matches File items`() {
            val directory = mockDirectory()
            val file = mockFile()

            PickerConstraint.IsFile.matches(file) shouldBe true
            PickerConstraint.IsFile.matches(directory) shouldBe false
        }

        @Test
        fun `IsStorage matches Storage items`() {
            val storage = mockStorage()
            val directory = mockDirectory()

            PickerConstraint.IsStorage.matches(storage) shouldBe true
            PickerConstraint.IsStorage.matches(directory) shouldBe false
        }

        @Test
        fun `IsShortcut matches Shortcut items`() {
            val shortcut = mockShortcut("home")
            val directory = mockDirectory()

            PickerConstraint.IsShortcut.matches(shortcut) shouldBe true
            PickerConstraint.IsShortcut.matches(directory) shouldBe false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Property Constraints
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class PropertyConstraints {

        @Test
        fun `IsEmpty matches directories with zero children`() {
            val emptyDir = mockDirectory(childCount = 0)
            val nonEmptyDir = mockDirectory(childCount = 5)
            val unknownDir = mockDirectory(childCount = null)

            PickerConstraint.IsEmpty.matches(emptyDir) shouldBe true
            PickerConstraint.IsEmpty.matches(nonEmptyDir) shouldBe false
            PickerConstraint.IsEmpty.matches(unknownDir) shouldBe false
        }

        @Test
        fun `IsEmpty returns false for non-directory items`() {
            val file = mockFile()

            PickerConstraint.IsEmpty.matches(file) shouldBe false
        }

        @Test
        fun `HasMimeType matches exact MIME types`() {
            val pdfFile = mockFile(mimeType = "application/pdf")
            val textFile = mockFile(mimeType = "text/plain")

            PickerConstraint.HasMimeType("application/pdf").matches(pdfFile) shouldBe true
            PickerConstraint.HasMimeType("application/pdf").matches(textFile) shouldBe false
        }

        @Test
        fun `HasMimeType matches wildcard patterns`() {
            val jpegFile = mockFile(mimeType = "image/jpeg")
            val pngFile = mockFile(mimeType = "image/png")
            val textFile = mockFile(mimeType = "text/plain")

            val imageConstraint = PickerConstraint.HasMimeType("image/*")

            imageConstraint.matches(jpegFile) shouldBe true
            imageConstraint.matches(pngFile) shouldBe true
            imageConstraint.matches(textFile) shouldBe false
        }

        @Test
        fun `HasMimeType returns false for non-file items`() {
            val directory = mockDirectory()

            PickerConstraint.HasMimeType("image/*").matches(directory) shouldBe false
        }

        @Test
        fun `MaxSize matches files within size limit`() {
            val smallFile = mockFile(size = 100L)
            val largeFile = mockFile(size = 10_000_000L)

            val constraint = PickerConstraint.MaxSize(1_000_000L)

            constraint.matches(smallFile) shouldBe true
            constraint.matches(largeFile) shouldBe false
        }

        @Test
        fun `MaxSize matches when size is exactly at limit`() {
            val file = mockFile(size = 1000L)

            PickerConstraint.MaxSize(1000L).matches(file) shouldBe true
        }

        @Test
        fun `MaxSize returns true for items with unknown size`() {
            val file = mockFile(size = null)

            PickerConstraint.MaxSize(1000L).matches(file) shouldBe true
        }

        @Test
        fun `MinSize matches files above size limit`() {
            val smallFile = mockFile(size = 100L)
            val largeFile = mockFile(size = 10_000_000L)

            val constraint = PickerConstraint.MinSize(1_000_000L)

            constraint.matches(smallFile) shouldBe false
            constraint.matches(largeFile) shouldBe true
        }

        @Test
        fun `MinSize returns false for items with unknown size`() {
            val file = mockFile(size = null)

            PickerConstraint.MinSize(1000L).matches(file) shouldBe false
        }

        @Test
        fun `HasShortcutId matches shortcuts with specific ID`() {
            val homeShortcut = mockShortcut("home")
            val trashShortcut = mockShortcut("trash")

            PickerConstraint.HasShortcutId("trash").matches(trashShortcut) shouldBe true
            PickerConstraint.HasShortcutId("trash").matches(homeShortcut) shouldBe false
        }

        @Test
        fun `HasShortcutId returns false for non-shortcut items`() {
            val directory = mockDirectory()

            PickerConstraint.HasShortcutId("home").matches(directory) shouldBe false
        }

        @Test
        fun `IsWritable matches lookup items where canWrite is true`() {
            val writableDir = mockDirectory(canWrite = true)
            val readOnlyDir = mockDirectory(canWrite = false)
            val unknownDir = mockDirectory(canWrite = null)

            PickerConstraint.IsWritable.matches(writableDir) shouldBe true
            PickerConstraint.IsWritable.matches(readOnlyDir) shouldBe false
            PickerConstraint.IsWritable.matches(unknownDir) shouldBe true // null = writable
        }

        @Test
        fun `IsWritable matches storage items where canWrite is not false`() {
            val writableStorage = mockStorage(canWrite = true)
            val readOnlyStorage = mockStorage(canWrite = false)
            val unknownStorage = mockStorage(canWrite = null)

            PickerConstraint.IsWritable.matches(writableStorage) shouldBe true
            PickerConstraint.IsWritable.matches(readOnlyStorage) shouldBe false
            PickerConstraint.IsWritable.matches(unknownStorage) shouldBe true
        }

        @Test
        fun `IsWritable returns true for non-Lookup non-Storage items`() {
            val shortcut = mockShortcut("home")

            PickerConstraint.IsWritable.matches(shortcut) shouldBe true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Logical Operators
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class LogicalOperators {

        @Test
        fun `And requires all constraints to match`() {
            val imageFile = mockFile(mimeType = "image/jpeg", size = 500L)

            val allMatch = PickerConstraint.And(
                listOf(
                    PickerConstraint.IsFile,
                    PickerConstraint.HasMimeType("image/*"),
                    PickerConstraint.MaxSize(1000L),
                )
            )

            val oneFails = PickerConstraint.And(
                listOf(
                    PickerConstraint.IsFile,
                    PickerConstraint.HasMimeType("video/*"),
                )
            )

            allMatch.matches(imageFile) shouldBe true
            oneFails.matches(imageFile) shouldBe false
        }

        @Test
        fun `And with empty list returns true`() {
            val file = mockFile()

            PickerConstraint.And(emptyList()).matches(file) shouldBe true
        }

        @Test
        fun `Or requires at least one constraint to match`() {
            val directory = mockDirectory()

            val oneMatches = PickerConstraint.Or(
                listOf(
                    PickerConstraint.IsFile,
                    PickerConstraint.IsDirectory,
                )
            )

            val noneMatch = PickerConstraint.Or(
                listOf(
                    PickerConstraint.IsFile,
                    PickerConstraint.IsStorage,
                )
            )

            oneMatches.matches(directory) shouldBe true
            noneMatch.matches(directory) shouldBe false
        }

        @Test
        fun `Or with empty list returns false`() {
            val file = mockFile()

            PickerConstraint.Or(emptyList()).matches(file) shouldBe false
        }

        @Test
        fun `Not inverts the constraint result`() {
            val file = mockFile()
            val directory = mockDirectory()

            val notFile = PickerConstraint.Not(PickerConstraint.IsFile)

            notFile.matches(file) shouldBe false
            notFile.matches(directory) shouldBe true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Terminal Constraints
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class TerminalConstraints {

        @Test
        fun `Any always matches`() {
            val file = mockFile()
            val directory = mockDirectory()
            val shortcut = mockShortcut("test")

            PickerConstraint.Any.matches(file) shouldBe true
            PickerConstraint.Any.matches(directory) shouldBe true
            PickerConstraint.Any.matches(shortcut) shouldBe true
        }

        @Test
        fun `None never matches`() {
            val file = mockFile()
            val directory = mockDirectory()
            val shortcut = mockShortcut("test")

            PickerConstraint.None.matches(file) shouldBe false
            PickerConstraint.None.matches(directory) shouldBe false
            PickerConstraint.None.matches(shortcut) shouldBe false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DSL Builders
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class DslBuilders {

        @Test
        fun `anyOf with no arguments returns None`() {
            anyOf() shouldBe PickerConstraint.None
        }

        @Test
        fun `anyOf with single argument returns that constraint`() {
            anyOf(PickerConstraint.IsFile) shouldBe PickerConstraint.IsFile
        }

        @Test
        fun `anyOf with multiple arguments returns Or`() {
            val result = anyOf(PickerConstraint.IsFile, PickerConstraint.IsDirectory)

            result shouldBe PickerConstraint.Or(
                listOf(PickerConstraint.IsFile, PickerConstraint.IsDirectory)
            )
        }

        @Test
        fun `allOf with no arguments returns Any`() {
            allOf() shouldBe PickerConstraint.Any
        }

        @Test
        fun `allOf with single argument returns that constraint`() {
            allOf(PickerConstraint.IsFile) shouldBe PickerConstraint.IsFile
        }

        @Test
        fun `allOf with multiple arguments returns And`() {
            val result = allOf(PickerConstraint.IsFile, PickerConstraint.MaxSize(1000L))

            result shouldBe PickerConstraint.And(
                listOf(PickerConstraint.IsFile, PickerConstraint.MaxSize(1000L))
            )
        }

        @Test
        fun `not returns Not constraint`() {
            not(PickerConstraint.IsFile) shouldBe PickerConstraint.Not(PickerConstraint.IsFile)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Selection Integration
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class SelectionIntegration {

        @Test
        fun `DirectorySingle selectable constraint matches directories and storage`() {
            val selection = PickerConfig.Selection.DirectorySingle

            selection.isSelectable(mockDirectory()) shouldBe true
            selection.isSelectable(mockStorage()) shouldBe true
            selection.isSelectable(mockFile()) shouldBe false
            selection.isSelectable(mockShortcut("home")) shouldBe false
        }

        @Test
        fun `DirectorySingle disabled constraint matches files and trash shortcut`() {
            val selection = PickerConfig.Selection.DirectorySingle

            selection.isDisabled(mockFile()) shouldBe true
            selection.isDisabled(mockShortcut("trash")) shouldBe true
            selection.isDisabled(mockShortcut("home")) shouldBe false
            selection.isDisabled(mockDirectory()) shouldBe false
        }

        @Test
        fun `FileSingle selectable constraint matches only files`() {
            val selection = PickerConfig.Selection.FileSingle

            selection.isSelectable(mockFile()) shouldBe true
            selection.isSelectable(mockDirectory()) shouldBe false
            selection.isSelectable(mockStorage()) shouldBe false
        }

        @Test
        fun `FileSingle disabled constraint matches nothing`() {
            val selection = PickerConfig.Selection.FileSingle

            selection.isDisabled(mockFile()) shouldBe false
            selection.isDisabled(mockDirectory()) shouldBe false
            selection.isDisabled(mockShortcut("trash")) shouldBe false
        }

        @Test
        fun `MixedMulti selectable constraint matches everything`() {
            val selection = PickerConfig.Selection.MixedMulti

            selection.isSelectable(mockFile()) shouldBe true
            selection.isSelectable(mockDirectory()) shouldBe true
            selection.isSelectable(mockStorage()) shouldBe true
            selection.isSelectable(mockShortcut("home")) shouldBe true
        }

        @Test
        fun `MixedMulti disabled constraint matches nothing`() {
            val selection = PickerConfig.Selection.MixedMulti

            selection.isDisabled(mockFile()) shouldBe false
            selection.isDisabled(mockDirectory()) shouldBe false
            selection.isDisabled(mockShortcut("trash")) shouldBe false
        }

        @Test
        fun `SaveAs requires writable directories and storage`() {
            val selection = PickerConfig.Selection.SaveAs("document.txt")

            // Selectable - must be directory/storage AND writable
            selection.isSelectable(mockDirectory(canWrite = true)) shouldBe true
            selection.isSelectable(mockDirectory(canWrite = null)) shouldBe true // unknown = writable
            selection.isSelectable(mockDirectory(canWrite = false)) shouldBe false
            selection.isSelectable(mockStorage(canWrite = true)) shouldBe true
            selection.isSelectable(mockStorage(canWrite = false)) shouldBe false
            selection.isSelectable(mockFile()) shouldBe false

            // Disabled - files, trash shortcut, and non-writable items
            selection.isDisabled(mockFile()) shouldBe true
            selection.isDisabled(mockShortcut("trash")) shouldBe true
            selection.isDisabled(mockDirectory(canWrite = true)) shouldBe false
            selection.isDisabled(mockDirectory(canWrite = false)) shouldBe true // read-only disabled
            selection.isDisabled(mockStorage(canWrite = false)) shouldBe true // read-only disabled
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Complex Constraint Composition
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ComplexComposition {

        @Test
        fun `image picker constraint - images under 5MB`() {
            val constraint = allOf(
                PickerConstraint.IsFile,
                PickerConstraint.HasMimeType("image/*"),
                PickerConstraint.MaxSize(5 * 1024 * 1024),
            )

            val smallJpeg = mockFile(mimeType = "image/jpeg", size = 1_000_000L)
            val largeJpeg = mockFile(mimeType = "image/jpeg", size = 10_000_000L)
            val smallPdf = mockFile(mimeType = "application/pdf", size = 1_000_000L)

            constraint.matches(smallJpeg) shouldBe true
            constraint.matches(largeJpeg) shouldBe false
            constraint.matches(smallPdf) shouldBe false
        }

        @Test
        fun `media picker constraint - images or videos`() {
            val constraint = allOf(
                PickerConstraint.IsFile,
                anyOf(
                    PickerConstraint.HasMimeType("image/*"),
                    PickerConstraint.HasMimeType("video/*"),
                ),
            )

            val image = mockFile(mimeType = "image/png")
            val video = mockFile(mimeType = "video/mp4")
            val audio = mockFile(mimeType = "audio/mp3")

            constraint.matches(image) shouldBe true
            constraint.matches(video) shouldBe true
            constraint.matches(audio) shouldBe false
        }

        @Test
        fun `directory picker excluding trash - directories but not trash shortcut`() {
            val constraint = anyOf(
                PickerConstraint.IsDirectory,
                PickerConstraint.IsStorage,
            )

            val disabledConstraint = anyOf(
                PickerConstraint.IsFile,
                allOf(PickerConstraint.IsShortcut, PickerConstraint.HasShortcutId("trash")),
            )

            val directory = mockDirectory()
            val storage = mockStorage()
            val file = mockFile()
            val trashShortcut = mockShortcut("trash")
            val homeShortcut = mockShortcut("home")

            // Selectable check
            constraint.matches(directory) shouldBe true
            constraint.matches(storage) shouldBe true
            constraint.matches(file) shouldBe false
            constraint.matches(trashShortcut) shouldBe false

            // Disabled check
            disabledConstraint.matches(file) shouldBe true
            disabledConstraint.matches(trashShortcut) shouldBe true
            disabledConstraint.matches(homeShortcut) shouldBe false
            disabledConstraint.matches(directory) shouldBe false
        }
    }
}
