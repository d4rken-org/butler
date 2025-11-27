package eu.darken.butler.explorer.ui.explorer.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

object MockDataProvider {

    private fun createMockLookup(
        name: String,
        path: String = "/test/$name",
        size: Long = 1024L,
        fileType: FileType = FileType.FILE,
        createdAt: Instant = Instant.parse("2023-10-10T10:30:00Z"),
        modifiedAt: Instant = Instant.parse("2023-10-15T10:30:00Z"),
        target: LocalPath? = null
    ): APathLookup<*> {
        // Create a mock implementation for previews with realistic metadata
        val mockOwnership = Ownership(
            userId = 1000,
            groupId = 1000,
            userName = "user",
            groupName = "user"
        )
        // File: 644 (rw-r--r--), Directory: 755 (rwxr-xr-x)
        val mockPermissions = when (fileType) {
            FileType.DIRECTORY -> Permissions(mode = 493) // 755 octal
            else -> Permissions(mode = 420) // 644 octal
        }

        return object : APathLookup<LocalPath> {
            override val lookedUp: LocalPath = LocalPath.build(path)
            override val size: Long = size
            override val fileType: FileType = fileType
            override val modifiedAt: Instant = modifiedAt
            override val target: LocalPath? = target
            override val ownership: Ownership = mockOwnership
            override val permissions: Permissions = mockPermissions
            override val createdAt: Instant = createdAt
            override val error: String? = null // No errors in mock preview data
        }
    }

    fun createMockDirectory(name: String = "Documents", childCount: Int? = 5): ExplorerItem.RegularDirectory {
        return ExplorerItem.RegularDirectory(
            lookup = createMockLookup(name, "/home/user/$name", 0L, FileType.DIRECTORY),
            childCount = childCount
        )
    }

    fun createMockSymbolicLink(
        name: String = "shortcut",
        targetPath: String? = "/home/user/target/file.txt",
        isBroken: Boolean = false
    ): ExplorerItem.SymbolicLink {
        val target = targetPath?.let { LocalPath.build(it) }
        return ExplorerItem.SymbolicLink(
            lookup = createMockLookup(name, "/home/user/$name", 0L, FileType.SYMBOLIC_LINK, target = target),
            mimeType = MimeInfo("inode/symlink"),
            targetPath = targetPath,
            isBroken = isBroken
        )
    }

    fun createMockRegularFile(name: String = "readme.txt"): ExplorerItem.RegularFile {
        return ExplorerItem.RegularFile(
            lookup = createMockLookup(name, "/home/user/$name", 4_096L),
            mimeType = MimeInfo("text/plain"),
        ).withExtendedData(
            ownership = Ownership(
                userId = 123,
                groupId = 456,
                userName = "aUser",
                groupName = "aGroup",
            ),
            permissions = Permissions(mode = 755),
            createdAt = Instant.parse("2023-10-10T10:30:00Z"),
        )
    }

    fun createMockPeek(name: String = "loading.txt"): ExplorerItem.Peek {
        return ExplorerItem.Peek(
            path = LocalPath.build("/home/user/$name")
        )
    }

    fun createAllFileTypes(): List<ExplorerItem.Lookup> {
        return listOf(
            createMockDirectory(),
            createMockSymbolicLink(),
            createMockSymbolicLink("broken_link", isBroken = true),
            createMockRegularFile(),
        )
    }

    // MARK: - Utility Helpers

    object MockSizes {
        const val KB = 1024L
        const val MB = KB * 1024
        const val GB = MB * 1024

        fun kb(value: Long) = value * KB
        fun mb(value: Long) = value * MB
        fun gb(value: Long) = value * GB
    }

    object MockTimes {
        fun hoursAgo(hours: Long): Instant = Clock.System.now() - hours.hours
        fun daysAgo(days: Long): Instant = Clock.System.now() - days.days
        fun minutesAgo(minutes: Long): Instant = Clock.System.now() - minutes.minutes
    }

    // MARK: - LocalPathLookup Factories

    fun createMockLocalPathLookup(
        path: String = "/storage/emulated/0/Documents/file.txt",
        name: String? = null,
        fileType: FileType = FileType.FILE,
        sizeKB: Long = 1024,
        hoursAgo: Long = 1
    ): LocalPathLookup {
        name ?: LocalPath.build(path).name
        return LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = MockSizes.kb(sizeKB),
            modifiedAt = MockTimes.hoursAgo(hoursAgo),
            target = null,
        )
    }

    fun createMockPdfFile(
        name: String = "document.pdf",
        sizeMB: Long = 5,
        hoursAgo: Long = 2
    ): LocalPathLookup {
        return createMockLocalPathLookup(
            path = "/storage/emulated/0/Documents/$name",
            fileType = FileType.FILE,
            sizeKB = sizeMB * 1024,
            hoursAgo = hoursAgo
        )
    }

    fun createMockImageFile(
        name: String = "photo.jpg",
        sizeKB: Long = 512,
        hoursAgo: Long = 1
    ): LocalPathLookup {
        return createMockLocalPathLookup(
            path = "/storage/emulated/0/Pictures/$name",
            fileType = FileType.FILE,
            sizeKB = sizeKB,
            hoursAgo = hoursAgo
        )
    }

    fun createMockVideoFile(
        name: String = "video.mp4",
        sizeMB: Long = 150,
        hoursAgo: Long = 3
    ): LocalPathLookup {
        return createMockLocalPathLookup(
            path = "/storage/emulated/0/Movies/$name",
            fileType = FileType.FILE,
            sizeKB = sizeMB * 1024,
            hoursAgo = hoursAgo
        )
    }

    fun createMockApkFile(
        name: String = "app.apk",
        sizeMB: Long = 25,
        hoursAgo: Long = 24
    ): LocalPathLookup {
        return createMockLocalPathLookup(
            path = "/storage/emulated/0/Downloads/$name",
            fileType = FileType.FILE,
            sizeKB = sizeMB * 1024,
            hoursAgo = hoursAgo
        )
    }

    fun createMockArchiveFile(
        name: String = "archive.zip",
        sizeMB: Long = 10,
        hoursAgo: Long = 6
    ): LocalPathLookup {
        return createMockLocalPathLookup(
            path = "/storage/emulated/0/Downloads/$name",
            fileType = FileType.FILE,
            sizeKB = sizeMB * 1024,
            hoursAgo = hoursAgo
        )
    }

    // MARK: - PathActionIssue Factories

    enum class ErrorType {
        IO, SECURITY, UNKNOWN
    }

    fun createMockUnknownErrorIssue(
        source: APathLookup<*>? = null,
        destination: APathLookup<*>? = null,
        errorType: ErrorType = ErrorType.IO,
        canRetry: Boolean = true,
        canSkip: Boolean = true
    ): PathActionIssue.UnknownError {
        val (exception, message) = when (errorType) {
            ErrorType.IO -> IOException("Input/output error") to "java.io.IOException: Input/output error"
            ErrorType.SECURITY -> SecurityException("Permission denied for this operation") to "java.lang.SecurityException: Permission denied for this operation"
            ErrorType.UNKNOWN -> RuntimeException("Unexpected vendor-specific error occurred") to "java.lang.RuntimeException: Unexpected vendor-specific error occurred"
        }

        return PathActionIssue.UnknownError(
            source = source,
            destination = destination,
            exception = exception,
            errorMessage = message.toCaString(),
            canSkip = canSkip,
            canRetry = canRetry,
        )
    }

    fun createMockPathExistsIssue(
        source: APathLookup<*> = createMockPdfFile("document.pdf"),
        destination: APathLookup<*> = createMockPdfFile("document.pdf", hoursAgo = 48),
        canOverwrite: Boolean = true
    ): PathActionIssue.PathAlreadyExists {
        return PathActionIssue.PathAlreadyExists(
            source = source,
            destination = destination,
            canSkip = true,
            canOverwrite = canOverwrite,
            canRenameSource = true,
        )
    }

    fun createMockPermissionIssue(
        destination: APathLookup<*> = createMockLocalPathLookup("/data/data/com.example.app/files/sensitive.dat"),
        source: APathLookup<*>? = null
    ): PathActionIssue.InsufficientPermission {
        return PathActionIssue.InsufficientPermission(
            source = source,
            destination = destination,
            canSkip = true,
        )
    }

    fun createMockInsufficientSpaceIssue(
        source: APathLookup<*> = createMockVideoFile("large_video.mp4", sizeMB = 4 * 1024), // 4GB
        destination: APathLookup<*> = createMockLocalPathLookup("/storage/external/large_video.mp4", sizeKB = 123)
    ): PathActionIssue.InsufficientSpace {
        return PathActionIssue.InsufficientSpace(
            source = source,
            destination = destination,
        )
    }

    // MARK: - Operation State Factories

    fun createMockRunningOperation(
        title: String = "Copying files",
        description: String = "Processing files...",
        icon: ImageVector = Icons.TwoTone.ContentCopy,
        progress: Int = 50,
        total: Int = 100,
        minutesAgo: Long = 2
    ): OperationDisplay {
        return OperationDisplay(
            id = Operation.Id(),
            title = title.toCaString(),
            description = "$progress of $total files".toCaString(),
            icon = icon,
            state = OperationDisplay.State.Running(
                primaryProgress = Progress.Data(
                    primary = title.toCaString(),
                    secondary = description.toCaString(),
                    count = Progress.Count.Counter(progress, total)
                )
            ),
            canCancel = true,
            startedAt = MockTimes.minutesAgo(minutesAgo),
        )
    }

    fun createMockCompletedOperation(
        title: String = "Move completed",
        description: String = "Successfully completed",
        icon: ImageVector = Icons.TwoTone.ContentCopy,
        filesAffected: Int = 5,
        minutesAgo: Long = 5
    ): OperationDisplay {
        return OperationDisplay(
            id = Operation.Id(),
            title = title.toCaString(),
            description = "$filesAffected files processed".toCaString(),
            icon = icon,
            state = OperationDisplay.State.Completed(
                summary = description.toCaString(),
                completedAt = MockTimes.minutesAgo(minutesAgo),
                report = object : Operation.Report {
                    override val summary = description.toCaString()
                    override val affectedPaths = emptyList<Operation.Report.PathChange>()
                }
            ),
            canCancel = false,
            startedAt = MockTimes.minutesAgo(minutesAgo + 2),
        )
    }

    fun createMockOperationsState(
        runningCount: Int = 1,
        completedCount: Int = 2,
        failedCount: Int = 0
    ): ExplorerWorkspaceViewModel.OperationsState {
        val operations = mutableListOf<OperationDisplay>()

        repeat(runningCount) { index ->
            operations.add(
                createMockRunningOperation(
                    title = when (index) {
                        0 -> "Deleting files"
                        1 -> "Moving documents"
                        else -> "Processing files #${index + 1}"
                    },
                    icon = when (index) {
                        0 -> Icons.TwoTone.Delete
                        1 -> Icons.TwoTone.ContentCut
                        else -> Icons.TwoTone.ContentCopy
                    },
                    minutesAgo = (index + 1).toLong()
                )
            )
        }

        repeat(completedCount) { index ->
            operations.add(
                createMockCompletedOperation(
                    title = when (index) {
                        0 -> "Copy operation"
                        1 -> "Archive created"
                        else -> "Operation #${index + 1} completed"
                    },
                    minutesAgo = (index + 3).toLong()
                )
            )
        }

        return ExplorerWorkspaceViewModel.OperationsState(operations = operations)
    }

    // MARK: - Clipboard State Factories

    fun createMockClipboardCopy(
        files: List<String> = listOf("photo1.jpg", "photo2.jpg", "photo3.jpg"),
        basePath: String = "/storage/emulated/0/Pictures",
        minutesAgo: Long = 1
    ): ClipboardClip.Paths {
        return ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = files.map { LocalPath.build("$basePath/$it") },
            clippedAt = MockTimes.minutesAgo(minutesAgo),
        )
    }

    fun createMockClipboardCut(
        files: List<String> = listOf("document.pdf"),
        basePath: String = "/storage/emulated/0/Documents",
        minutesAgo: Long = 2
    ): ClipboardClip.Paths {
        return ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = files.map { LocalPath.build("$basePath/$it") },
            clippedAt = MockTimes.minutesAgo(minutesAgo),
        )
    }

    fun createMockClipboardState(
        copyCount: Int = 1,
        cutCount: Int = 1
    ): ExplorerWorkspaceViewModel.ClipboardState {
        val entries = mutableListOf<ClipboardClip.Paths>()

        repeat(copyCount) { index ->
            entries.add(
                createMockClipboardCopy(
                    files = when (index) {
                        0 -> listOf("photo1.jpg", "photo2.jpg", "photo3.jpg")
                        1 -> listOf("app.apk")
                        else -> listOf("file${index + 1}.txt")
                    },
                    basePath = when (index) {
                        0 -> "/storage/emulated/0/Pictures"
                        1 -> "/storage/emulated/0/Downloads"
                        else -> "/storage/emulated/0/Documents"
                    },
                    minutesAgo = (index + 1).toLong()
                )
            )
        }

        repeat(cutCount) { index ->
            entries.add(
                createMockClipboardCut(
                    files = when (index) {
                        0 -> listOf("report.pdf")
                        else -> listOf("cut_file${index + 1}.doc")
                    },
                    minutesAgo = (copyCount + index + 1).toLong()
                )
            )
        }

        return ExplorerWorkspaceViewModel.ClipboardState(entries = entries)
    }

    // MARK: - Storage and Shortcut Factories

    fun createMockStorageLocal(
        localId: String = "internal-public",
        name: String = "Internal Storage",
        icon: ImageVector = Icons.TwoTone.Storage
    ): ExplorerItem.Storage.Local {
        return ExplorerItem.Storage.Local(
            localId = localId,
            displayName = name.toCaString(),
            displayIcon = icon,
            totalBytes = 128 * 1024 * 1024 * 1024L,
            availableBytes = 64 * 1024 * 1024 * 1024L,
            target = ExplorerNavigation.Target.Directory(
                LocalPath.build("/storage/emulated/0")
            )
        )
    }

    fun createMockStorageSAF(
        name: String = "SD Card",
        icon: ImageVector = Icons.TwoTone.FolderShared,
        hasReadPermission: Boolean = true,
        hasWritePermission: Boolean = true,
    ): ExplorerItem.Storage.SAF {
        return ExplorerItem.Storage.SAF(
            location = SAFLocation(
                id = "saf-mock-id",
                treeUri = SafUri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments"),
                path = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments"),
                hasReadPermission = hasReadPermission,
                hasWritePermission = hasWritePermission,
                grantedAt = MockTimes.daysAgo(7),
                userLabel = name,
            ),
            displayName = name.toCaString(),
            displayIcon = icon,
            totalBytes = 999 * 1024 * 1024 * 1024L,
            availableBytes = 555 * 1024 * 1024 * 1024L,
            target = ExplorerNavigation.Target.Directory(
                SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
            )
        )
    }

    fun createMockShortcut(
        shortcutId: String = "device",
        name: String = "Device",
        icon: ImageVector = Icons.TwoTone.PhoneAndroid
    ): ExplorerItem.Shortcut {
        return ExplorerItem.Shortcut(
            shortcutId = shortcutId,
            displayName = name.toCaString(),
            displayIcon = icon,
            target = ExplorerNavigation.Target.Device
        )
    }

    // MARK: - Trash Item Factories

    fun createMockTrashItem(
        name: String = "deleted_file.txt",
        originalPath: String = "/storage/emulated/0/Documents",
        sizeKB: Long = 128,
        deletedHoursAgo: Long = 2,
        isAvailable: Boolean = true,
    ): ExplorerItem.Trash.Root {
        val originalLookup = createMockLocalPathLookup(
            path = "$originalPath/$name",
            fileType = FileType.FILE,
            sizeKB = sizeKB,
            hoursAgo = deletedHoursAgo + 24, // Original file modified before deletion
        )
        val trashLookup = if (isAvailable) {
            createMockLocalPathLookup(
                path = "/data/user/0/eu.darken.butler/trash/${Uuid.random()}/$name",
                fileType = FileType.FILE,
                sizeKB = sizeKB,
                hoursAgo = deletedHoursAgo,
            )
        } else null

        return ExplorerItem.Trash.Root(
            itemId = Uuid.random(),
            deletedAt = MockTimes.hoursAgo(deletedHoursAgo),
            originalLookup = originalLookup,
            trashLookup = trashLookup,
        )
    }

    fun createMockTrashItemOld(
        name: String = "old_backup.zip",
        originalPath: String = "/storage/emulated/0/Downloads",
        sizeKB: Long = 5120,
        deletedDaysAgo: Long = 14,
    ): ExplorerItem.Trash.Root {
        val originalLookup = createMockLocalPathLookup(
            path = "$originalPath/$name",
            fileType = FileType.FILE,
            sizeKB = sizeKB,
            hoursAgo = deletedDaysAgo * 24 + 48,
        )
        val trashLookup = createMockLocalPathLookup(
            path = "/data/user/0/eu.darken.butler/trash/${Uuid.random()}/$name",
            fileType = FileType.FILE,
            sizeKB = sizeKB,
            hoursAgo = deletedDaysAgo * 24,
        )

        return ExplorerItem.Trash.Root(
            itemId = Uuid.random(),
            deletedAt = MockTimes.daysAgo(deletedDaysAgo),
            originalLookup = originalLookup,
            trashLookup = trashLookup,
        )
    }

    // MARK: - Trash Nested Item Factories

    private fun createMockParentRef(
        originalPath: String = "/storage/emulated/0/Documents/MyFolder",
        trashPath: String = "/data/user/0/eu.darken.butler/trash",
    ): TrashItemReference {
        val itemId = Uuid.random()
        return TrashItemReference(
            itemId = itemId,
            displayName = originalPath.substringAfterLast("/").toCaString(),
            originalPath = LocalPath.build(originalPath),
            trashPath = LocalPath.build("$trashPath/$itemId"),
            deletedAt = MockTimes.hoursAgo(2),
        )
    }

    fun createMockTrashNestedItem(
        name: String = "nested_file.txt",
        relativePath: String? = null,
        sizeKB: Long = 64,
    ): ExplorerItem.Trash.Nested {
        val actualRelativePath = relativePath ?: name
        val parentRef = createMockParentRef()

        val lookup = createMockLocalPathLookup(
            path = "${parentRef.trashPath.path}/$actualRelativePath",
            fileType = FileType.FILE,
            sizeKB = sizeKB,
            hoursAgo = 2,
        )

        return ExplorerItem.Trash.Nested(
            inner = ExplorerItem.RegularFile(
                lookup = lookup,
                mimeType = MimeInfo("application/octet-stream"),
            ),
            parentRef = parentRef,
            relativePath = actualRelativePath,
        )
    }

    fun createMockTrashNestedDirectory(
        name: String = "nested_folder",
        relativePath: String? = null,
        childCount: Int = 5,
    ): ExplorerItem.Trash.Nested {
        val actualRelativePath = relativePath ?: name
        val parentRef = createMockParentRef()

        val lookup = createMockLocalPathLookup(
            path = "${parentRef.trashPath.path}/$actualRelativePath",
            fileType = FileType.DIRECTORY,
            sizeKB = 0,
            hoursAgo = 2,
        )

        return ExplorerItem.Trash.Nested(
            inner = ExplorerItem.RegularDirectory(
                lookup = lookup,
                childCount = childCount,
            ),
            parentRef = parentRef,
            relativePath = actualRelativePath,
        )
    }
}