package eu.darken.butler.explorer.ui.explorer.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.icons.SmbShare
import eu.darken.butler.common.files.APath
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
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.storage.saf.StorageProviderApp
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
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

    fun createMockRegularFile(
        name: String = "readme.txt",
        modifiedAt: Instant = Instant.parse("2023-10-15T10:30:00Z"),
    ): ExplorerItem.RegularFile {
        return ExplorerItem.RegularFile(
            lookup = createMockLookup(name, "/home/user/$name", 4_096L, modifiedAt = modifiedAt),
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
            destinationPath = destination?.lookedUp,
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
            destinationPath = destination.lookedUp,
            canSkip = true,
        )
    }

    fun createMockInsufficientSpaceIssue(
        source: APathLookup<*> = createMockVideoFile("large_video.mp4", sizeMB = 4 * 1024), // 4GB
        destination: APathLookup<*> = createMockLocalPathLookup("/storage/external/large_video.mp4", sizeKB = 123)
    ): PathActionIssue.InsufficientSpace {
        return PathActionIssue.InsufficientSpace(
            source = source,
            destinationPath = destination.lookedUp,
        )
    }

    fun createMockTrashSizeLimitIssue(
        totalSize: Long = 700L * 1024 * 1024, // 700MB
        itemCount: Int = 5,
        trashMaxSize: Long = 500L * 1024 * 1024, // 500MB
    ): PathActionIssue.TrashSizeLimitExceeded {
        return PathActionIssue.TrashSizeLimitExceeded(
            totalSize = totalSize,
            itemCount = itemCount,
            trashMaxSize = trashMaxSize,
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
                    override val subjectPath = null
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
    ): OperationsDisplayState {
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

        return OperationsDisplayState(operations = operations)
    }

    // MARK: - Clipboard State Factories

    private fun mockFileLookup(path: String): LocalPathLookup = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = null,
        modifiedAt = null,
    )

    fun createMockClipboardCopy(
        files: List<String> = listOf("photo1.jpg", "photo2.jpg", "photo3.jpg"),
        basePath: String = "/storage/emulated/0/Pictures",
        minutesAgo: Long = 1
    ): ClipboardClip.Paths {
        return ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = files.map { mockFileLookup("$basePath/$it") },
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
            paths = files.map { mockFileLookup("$basePath/$it") },
            clippedAt = MockTimes.minutesAgo(minutesAgo),
        )
    }

    fun createMockClipboardState(
        copyCount: Int = 1,
        cutCount: Int = 1
    ): ClipboardDisplayState {
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

        return ClipboardDisplayState(entries = entries)
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

    const val SAF_TREE_URI_DOCUMENTS = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    const val SAF_TREE_URI_SDCARD = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"
    const val SAF_TREE_URI_USB = "content://com.android.externalstorage.documents/tree/18E4-9F02%3A"

    /**
     * [id] and [treeUri] are parameters because the list key and the navigation target both derive
     * from them: two calls sharing them would be the same entry twice.
     */
    fun createMockStorageSAF(
        name: String = "SD Card",
        icon: ImageVector = Icons.TwoTone.FolderShared,
        hasReadPermission: Boolean = true,
        hasWritePermission: Boolean = true,
        id: String = "saf-mock-id",
        treeUri: String = SAF_TREE_URI_DOCUMENTS,
        totalBytes: Long = MockSizes.gb(999),
        availableBytes: Long = MockSizes.gb(555),
        providerApp: StorageProviderApp? = null,
    ): ExplorerItem.Storage.SAF {
        return ExplorerItem.Storage.SAF(
            location = SAFLocation(
                id = id,
                treeUri = SafUri.parse(treeUri),
                path = SAFPath.build(treeUri),
                hasReadPermission = hasReadPermission,
                hasWritePermission = hasWritePermission,
                grantedAt = MockTimes.daysAgo(7),
                userLabel = name,
            ),
            displayName = name.toCaString(),
            displayIcon = icon,
            totalBytes = totalBytes,
            availableBytes = availableBytes,
            target = ExplorerNavigation.Target.Directory(SAFPath.build(treeUri)),
            providerApp = providerApp,
        )
    }

    fun createMockStorageNetwork(
        name: String = "Home NAS",
        host: String = "nas.local",
        share: String = "media",
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
        id: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        endpoint: SmbEndpointState = SmbEndpointState(),
        username: String? = "hoffmann",
        domain: String? = null,
        lastSeenAt: Instant? = null,
    ): ExplorerItem.Storage.Network {
        val location = SmbLocation(
            id = id,
            label = name,
            host = host,
            share = share,
            domain = domain,
            username = username,
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            credentialVersion = 1,
            createdAt = MockTimes.daysAgo(7),
            updatedAt = MockTimes.daysAgo(7),
            lastSeenAt = lastSeenAt,
        )
        return ExplorerItem.Storage.Network(
            location = location,
            displayName = name.toCaString(),
            displayIcon = Icons.TwoTone.SmbShare,
            target = ExplorerNavigation.Target.Directory(location.rootPath),
            subtitle = location.endpointLabel.toCaString(),
            credentials = when (status) {
                ExplorerItem.Storage.Network.Status.AVAILABLE -> SmbCredentialStore.Availability.AVAILABLE
                ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED -> SmbCredentialStore.Availability.MISSING
            },
            endpoint = endpoint,
        )
    }

    fun createMockShortcut(
        shortcutId: String = "device",
        name: String = "Device",
        icon: ImageVector = Icons.TwoTone.PhoneAndroid,
        target: ExplorerNavigation.Target = ExplorerNavigation.Target.Device,
    ): ExplorerItem.Shortcut {
        return ExplorerItem.Shortcut(
            shortcutId = shortcutId,
            displayName = name.toCaString(),
            displayIcon = icon,
            target = target,
        )
    }

    /** Localized like the real home screen's, unlike the plain-string [createMockShortcut]. */
    fun createDeviceShortcut(): ExplorerItem.Shortcut = ExplorerItem.Shortcut(
        shortcutId = "device",
        displayName = R.string.explorer_navigation_device.toCaString(),
        displayIcon = Icons.TwoTone.PhoneAndroid,
        target = ExplorerNavigation.Target.Device,
        // Same shape as HomeLocationLoader's "${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})".
        subtitle = caString { "Nimbus P9 (Android 16, API 36)" },
    )

    fun createTrashShortcut(
        itemCount: Int = 12,
        totalSize: Long = MockSizes.mb(350),
    ): ExplorerItem.Shortcut = ExplorerItem.Shortcut(
        shortcutId = "trash",
        displayName = R.string.explorer_navigation_trash.toCaString(),
        displayIcon = Icons.TwoTone.Delete,
        target = ExplorerNavigation.Target.Trash.Root,
        subtitle = caString { cx ->
            val countText = cx.resources.getQuantityString(
                R.plurals.explorer_trash_item_count,
                itemCount,
                itemCount,
            )
            "$countText • ${formatFileSize(cx, totalSize)} "
        },
    )

    // MARK: - Favorite Factories

    private fun favoriteDirectory(
        path: APath<*>,
        childCount: Int,
        daysAgo: Long,
    ): FavoriteItem = FavoriteItem(
        path = path,
        state = FavoriteItem.State.Available(
            ExplorerItem.RegularDirectory(
                lookup = createMockLookup(
                    name = path.name,
                    path = path.path,
                    size = 0L,
                    fileType = FileType.DIRECTORY,
                    modifiedAt = MockTimes.daysAgo(daysAgo),
                ),
                childCount = childCount,
            ),
        ),
    )

    fun createMockFavorites(): List<FavoriteItem> = listOf(
        favoriteDirectory(LocalPath.build("/storage/emulated/0/Download"), childCount = 41, daysAgo = 1),
        favoriteDirectory(LocalPath.build("/storage/emulated/0/DCIM/Camera"), childCount = 312, daysAgo = 1),
        favoriteDirectory(LocalPath.build("/storage/emulated/0/Projects/butler"), childCount = 18, daysAgo = 4),
        // Same SD identity as the home screen's storage entry, so the unavailable treatment reads
        // as "the card is gone", not as an unrelated path.
        FavoriteItem(
            path = SAFPath.build(SAF_TREE_URI_SDCARD, "Backups"),
            state = FavoriteItem.State.Unavailable(IOException("Storage not mounted")),
        ),
    )

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

    // MARK: - Breadcrumb Factories

    fun createHomeBreadcrumb(): ExplorerBreadcrumb {
        return ExplorerBreadcrumb(
            label = R.string.explorer_navigation_home.toCaString(),
            icon = Icons.TwoTone.Home,
            target = ExplorerNavigation.Target.Home,
        )
    }

    fun createStorageBreadcrumbs(): List<ExplorerBreadcrumb> {
        return listOf(
            createHomeBreadcrumb(),
            ExplorerBreadcrumb(
                label = "storage".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "emulated".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "0".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "Some".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "FolderA".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some/FolderA")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "FolderB".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some/FolderA/FolderB")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "FolderC".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some/FolderA/FolderB/FolderC")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "FolderD".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some/FolderA/FolderB/FolderC/FolderD")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "FileA".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Some/FolderA/FolderB/FolderC/FileA")),
                icon = Icons.TwoTone.FolderOpen,
            ),
        )
    }

    fun createDownloadBreadcrumbs(): List<ExplorerBreadcrumb> {
        return listOf(
            createHomeBreadcrumb(),
            ExplorerBreadcrumb(
                label = "sdcard".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard")),
                icon = Icons.TwoTone.FolderOpen,
            ),
            ExplorerBreadcrumb(
                label = "Download".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Download")),
                icon = Icons.TwoTone.FolderOpen,
            ),
        )
    }

    fun createDeviceBreadcrumbs(): List<ExplorerBreadcrumb> = listOf(
        createHomeBreadcrumb(),
        ExplorerBreadcrumb(
            label = R.string.explorer_navigation_device.toCaString(),
            icon = Icons.TwoTone.PhoneAndroid,
            target = ExplorerNavigation.Target.Device,
        ),
    )

    fun createDeviceRootBreadcrumbs(): List<ExplorerBreadcrumb> = listOf(
        createHomeBreadcrumb(),
        ExplorerBreadcrumb(
            label = "storage".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage")),
            icon = Icons.TwoTone.FolderOpen,
        ),
        ExplorerBreadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated")),
            icon = Icons.TwoTone.FolderOpen,
        ),
        ExplorerBreadcrumb(
            label = "0".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0")),
            icon = Icons.TwoTone.FolderOpen,
        ),
    )

    // MARK: - Android Device Listing

    private const val DEVICE_ROOT = "/storage/emulated/0"

    /**
     * [hoursAgo] rather than whole days on purpose: a day-aligned offset gives every row the exact
     * same clock time, and a screenshot of twenty rows all modified at 5:21:40 PM reads as fake.
     * Keep these off multiples of 24.
     */
    private fun deviceDirectory(
        name: String,
        childCount: Int,
        hoursAgo: Long,
    ): ExplorerItem.RegularDirectory = ExplorerItem.RegularDirectory(
        lookup = createMockLookup(
            name = name,
            path = "$DEVICE_ROOT/$name",
            size = 0L,
            fileType = FileType.DIRECTORY,
            modifiedAt = MockTimes.hoursAgo(hoursAgo),
        ),
        childCount = childCount,
    )

    private fun deviceFile(
        name: String,
        size: Long,
        mimeType: String,
        hoursAgo: Long,
    ): ExplorerItem.RegularFile = ExplorerItem.RegularFile(
        lookup = createMockLookup(
            name = name,
            path = "$DEVICE_ROOT/$name",
            size = size,
            modifiedAt = MockTimes.hoursAgo(hoursAgo),
        ),
        mimeType = MimeInfo(mimeType),
    )

    /**
     * A realistic `/storage/emulated/0` listing for the Play Store screenshot.
     *
     * Deliberately not folders-first: only around eight rows fit above the fold on a phone, and a
     * sorted listing would push every file — and with it every file-type icon — out of the shot.
     */
    fun createAndroidDeviceListing(): List<ExplorerItem.Path> = listOf(
        deviceDirectory("Alarms", childCount = 3, hoursAgo = 2305),
        deviceDirectory("Android", childCount = 12, hoursAgo = 19),
        deviceDirectory("DCIM", childCount = 2, hoursAgo = 26),
        deviceFile("IMG_20260712_181402.jpg", MockSizes.mb(4), "image/jpeg", hoursAgo = 3),
        deviceDirectory("Documents", childCount = 27, hoursAgo = 43),
        deviceFile("VID_20260705_094512.mp4", MockSizes.mb(148), "video/mp4", hoursAgo = 52),
        deviceDirectory("Download", childCount = 41, hoursAgo = 31),
        deviceFile("invoice_july.pdf", MockSizes.kb(820), "application/pdf", hoursAgo = 118),
        deviceDirectory("Audiobooks", childCount = 6, hoursAgo = 823),
        deviceDirectory("Movies", childCount = 4, hoursAgo = 295),
        deviceDirectory("Music", childCount = 128, hoursAgo = 197),
        deviceFile("Recording_014.m4a", MockSizes.mb(12), "audio/mp4", hoursAgo = 27),
        deviceDirectory("Notifications", childCount = 5, hoursAgo = 2311),
        deviceDirectory("Pictures", childCount = 312, hoursAgo = 70),
        deviceFile("backup-2026-07-19.zip", MockSizes.mb(512), "application/zip", hoursAgo = 209),
        deviceDirectory("Podcasts", childCount = 9, hoursAgo = 511),
        deviceFile("butler-1.4.2.apk", MockSizes.mb(18), "application/vnd.android.package-archive", hoursAgo = 139),
        deviceDirectory("Ringtones", childCount = 14, hoursAgo = 2318),
        deviceFile("notes.md", MockSizes.kb(6), "text/markdown", hoursAgo = 4),
        deviceFile("readme.txt", MockSizes.kb(2), "text/plain", hoursAgo = 713),
    )

    // MARK: - Location Info Factories

    /** Counts and size are derived from [items] so the info chips match what the listing shows. */
    fun createAndroidDeviceInfo(
        items: List<ExplorerItem.Path> = createAndroidDeviceListing(),
    ): ExplorerLocation.Directory.Info = createMockDirectoryInfo(
        fileCount = items.count { it is ExplorerItem.File },
        directoryCount = items.count { it is ExplorerItem.Directory },
        totalSize = items.filterIsInstance<ExplorerItem.File>().sumOf { it.lookup.size ?: 0L },
        freeSpace = MockSizes.gb(53),
        totalSpace = MockSizes.gb(128),
    )

    fun createMockDirectoryInfo(
        fileCount: Int = 15,
        directoryCount: Int = 5,
        totalSize: Long = MockSizes.mb(250),
        freeSpace: Long = MockSizes.gb(50),
        totalSpace: Long = MockSizes.gb(128),
        isWritable: Boolean = true,
        isReadable: Boolean = true,
    ): ExplorerLocation.Directory.Info = ExplorerLocation.Directory.Info(
        fileCount = fileCount,
        directoryCount = directoryCount,
        totalSize = totalSize,
        volumeFreeSpace = freeSpace,
        volumeTotalSpace = totalSpace,
        isWritable = isWritable,
        isReadable = isReadable,
    )

    fun createMockEmptyDirectoryInfo(
        freeSpace: Long = MockSizes.gb(50),
        totalSpace: Long = MockSizes.gb(128),
    ): ExplorerLocation.Directory.Info = createMockDirectoryInfo(
        fileCount = 0,
        directoryCount = 0,
        totalSize = 0L,
        freeSpace = freeSpace,
        totalSpace = totalSpace,
    )

    fun createMockHomeInfo(
        shortcutCount: Int = 5,
        totalDeviceStorage: Long = MockSizes.gb(128),
        usedStorage: Long = MockSizes.gb(78),
    ): ExplorerLocation.Home.Info = ExplorerLocation.Home.Info(
        shortcutCount = shortcutCount,
        totalDeviceStorage = totalDeviceStorage,
        usedStorage = usedStorage,
    )

    /** Counts and capacity derived from [items] so the info chips match the listed storages. */
    fun createDeviceInfoFor(items: List<ExplorerItem>): ExplorerLocation.Device.Info {
        val storages = items.filterIsInstance<ExplorerItem.Storage>()
        return createMockDeviceInfo(
            locationCount = items.size,
            totalCapacity = storages.sumOf { it.totalBytes ?: 0L },
            usedSpace = storages.sumOf { (it.totalBytes ?: 0L) - (it.availableBytes ?: 0L) },
        )
    }

    fun createMockDeviceInfo(
        locationCount: Int = 2,
        totalCapacity: Long = MockSizes.gb(256),
        usedSpace: Long = MockSizes.gb(120),
    ): ExplorerLocation.Device.Info = ExplorerLocation.Device.Info(
        locationCount = locationCount,
        totalCapacity = totalCapacity,
        usedSpace = usedSpace,
    )

    fun createMockTrashRootInfo(
        itemCount: Int = 12,
        totalSize: Long = MockSizes.mb(350),
        oldestItem: Instant? = MockTimes.daysAgo(7),
    ): ExplorerLocation.Trash.Root.Info = ExplorerLocation.Trash.Root.Info(
        itemCount = itemCount,
        totalSize = totalSize,
        oldestItem = oldestItem,
    )

    fun createMockTrashNestedInfo(
        fileCount: Int = 8,
        directoryCount: Int = 3,
        totalSize: Long = MockSizes.mb(120),
    ): ExplorerLocation.Trash.Nested.Info = ExplorerLocation.Trash.Nested.Info(
        fileCount = fileCount,
        directoryCount = directoryCount,
        totalSize = totalSize,
    )

    // MARK: - Location Factories

    fun createMockDirectoryLocation(
        path: String = "/storage/emulated/0",
        items: List<ExplorerItem.Path> = createAllFileTypes(),
        info: ExplorerLocation.Directory.Info = createMockDirectoryInfo(),
        progress: Progress.Data? = null,
    ): ExplorerLocation.Directory = ExplorerLocation.Directory(
        path = LocalPath.build(path),
        items = items,
        info = info,
        progress = progress,
    )

    fun createMockEmptyDirectoryLocation(
        path: String = "/sdcard/EmptyFolder",
    ): ExplorerLocation.Directory = createMockDirectoryLocation(
        path = path,
        items = emptyList(),
        info = createMockEmptyDirectoryInfo(),
    )

    /**
     * The two shortcuts `HomeLocationLoader` builds, in its order. Home never lists storages —
     * those belong to the device location, see [createMockDeviceItems].
     */
    fun createMockHomeItems(): List<ExplorerItem> = listOf(
        createDeviceShortcut(),
        createTrashShortcut(),
    )

    fun createMockDeviceItems(): List<ExplorerItem> = listOf(
        createMockStorageLocal(),
        createMockStorageSAF(
            name = "SD Card",
            id = "saf-sdcard",
            treeUri = SAF_TREE_URI_SDCARD,
            totalBytes = MockSizes.gb(512),
            availableBytes = MockSizes.gb(213),
        ),
        createMockStorageSAF(
            name = "USB Drive",
            icon = Icons.TwoTone.Usb,
            id = "saf-usb",
            treeUri = SAF_TREE_URI_USB,
            totalBytes = MockSizes.gb(64),
            availableBytes = MockSizes.gb(61),
        ),
    )

    fun createMockHomeLocation(
        items: List<ExplorerItem> = createMockHomeItems(),
        info: ExplorerLocation.Home.Info = createMockHomeInfo(shortcutCount = items.size),
    ): ExplorerLocation.Home = ExplorerLocation.Home(
        items = items,
        info = info,
        progress = null,
    )

    fun createMockDeviceLocation(
        items: List<ExplorerItem> = createMockDeviceItems(),
        info: ExplorerLocation.Device.Info = createDeviceInfoFor(items),
    ): ExplorerLocation.Device = ExplorerLocation.Device(
        items = items,
        info = info,
        progress = null,
    )

    // MARK: - Progress Factory

    fun createMockProgress(
        primary: String = "Loading",
        secondary: String = "Processing files…",
        current: Int = 50,
        total: Int = 100,
    ): Progress.Data = Progress.Data(
        primary = primary.toCaString(),
        secondary = secondary.toCaString(),
        count = Progress.Count.Counter(current, total),
    )

    fun createMockIndeterminateProgress(
        secondary: String = "Checking permissions…",
    ): Progress.Data = Progress.Data(
        secondary = secondary.toCaString(),
        count = Progress.Count.Indeterminate(),
    )

    // MARK: - Action Bar Factories

    /** Mirrors [eu.darken.butler.explorer.ui.explorer.actions.HomeActionProvider]. */
    fun createDefaultHomeActions(
        viewStyle: ExplorerViewStyle = ExplorerViewStyle.default(),
    ): List<ExplorerActionBarItem> = listOf(
        ExplorerActionBarItem.Common.Sort(),
        ExplorerActionBarItem.Common.Filter(),
        ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()),
        ExplorerActionBarItem.Common.Refresh(),
    )

    /**
     * Mirrors [eu.darken.butler.explorer.ui.explorer.actions.DeviceActionProvider] with nothing
     * selected: add-location first, then the shared actions.
     */
    fun createDefaultDeviceActions(
        viewStyle: ExplorerViewStyle = ExplorerViewStyle.default(),
    ): List<ExplorerActionBarItem> = listOf(ExplorerActionBarItem.Device.AddLocation()) +
        createDefaultHomeActions(viewStyle)

    fun createDefaultDirectoryActions(
        createEnabled: Boolean = true,
        filterEnabled: Boolean = true,
    ): List<ExplorerActionBarItem> = listOf(
        ExplorerActionBarItem.Directory.Create(isEnabled = createEnabled),
        ExplorerActionBarItem.Common.Sort(),
        ExplorerActionBarItem.Common.Filter(isEnabled = filterEnabled),
    )

    /**
     * What the action bar offers once rows are selected, mirroring what `DirectoryActionProvider`
     * swaps in. Pair with a non-empty [ExplorerSelectionState] - browse actions next to a selection
     * would show a state the app never produces.
     */
    fun createSelectionActions(
        trashEnabled: Boolean = true,
    ): List<ExplorerActionBarItem> = listOf(
        ExplorerActionBarItem.Directory.Copy(),
        ExplorerActionBarItem.Directory.Cut(),
        ExplorerActionBarItem.Directory.Delete(trashEnabled = trashEnabled),
        ExplorerActionBarItem.Directory.Share(),
        ExplorerActionBarItem.Directory.Compress(),
    )

    // MARK: - State Factories

    fun createReadyState(
        location: ExplorerLocation.Directory = createMockDirectoryLocation(),
        breadcrumbs: List<ExplorerBreadcrumb> = createStorageBreadcrumbs(),
        actions: List<ExplorerActionBarItem> = createDefaultDirectoryActions(),
        selectionState: ExplorerSelectionState = ExplorerSelectionState(),
    ): ExplorerWorkspaceViewModel.State = ExplorerWorkspaceViewModel.State(
        currentLocation = location,
        breadcrumbs = breadcrumbs,
        items = location.items,
        availableActions = actions,
        selectionState = selectionState,
    )

    fun createEmptyState(
        path: String = "/sdcard/EmptyFolder",
        breadcrumbs: List<ExplorerBreadcrumb> = createDownloadBreadcrumbs(),
    ): ExplorerWorkspaceViewModel.State = createReadyState(
        location = createMockEmptyDirectoryLocation(path),
        breadcrumbs = breadcrumbs,
    )

    fun createErrorState(
        error: Throwable,
    ): ExplorerWorkspaceViewModel.State = ExplorerWorkspaceViewModel.State(
        currentLocation = null,
        breadcrumbs = emptyList(),
        items = null,
        error = error,
    )

    fun createPickerState(
        selection: PickerConfig.Selection = PickerConfig.Selection.MixedMulti,
        items: List<ExplorerItem.Path> = createAllFileTypes() + listOf(
            createMockDirectory("Photos", childCount = 234),
            createMockDirectory("Videos", childCount = 56),
            createMockDirectory("Music", childCount = 189),
        ),
        selectedItems: Set<ExplorerItem> = emptySet(),
        path: String = "/sdcard/Documents",
    ): ExplorerWorkspaceViewModel.State {
        val pickerConfig = PickerConfig(
            selection = selection,
            callerWorkspaceId = Workspace.Id(),
        )
        return ExplorerWorkspaceViewModel.State(
            pickerConfig = pickerConfig,
            currentLocation = ExplorerLocation.Directory(
                path = LocalPath.build(path),
                items = items,
                info = createMockDirectoryInfo(
                    fileCount = items.count { it is ExplorerItem.File },
                    directoryCount = items.count { it is ExplorerItem.RegularDirectory },
                ),
                progress = null,
            ),
            breadcrumbs = listOf(
                createHomeBreadcrumb(),
                ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard")),
                    icon = Icons.TwoTone.FolderOpen,
                ),
                ExplorerBreadcrumb(
                    label = "Documents".toCaString(),
                    target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Documents")),
                    icon = Icons.TwoTone.FolderOpen,
                ),
            ),
            items = items,
            selectionState = ExplorerSelectionState(
                selectedItems = selectedItems,
                selectableItems = items.toSet(),
            ),
        )
    }

    fun createStateWithSelection(
        location: ExplorerLocation.Directory = createMockDirectoryLocation(),
        breadcrumbs: List<ExplorerBreadcrumb> = createStorageBreadcrumbs(),
        selectedIndices: List<Int> = listOf(0, 2),
    ): ExplorerWorkspaceViewModel.State {
        val items = location.items ?: emptyList()
        val selectedItems = selectedIndices.mapNotNull { items.getOrNull(it) }.toSet()
        return createReadyState(
            location = location,
            breadcrumbs = breadcrumbs,
            selectionState = ExplorerSelectionState(
                selectedItems = selectedItems,
                selectableItems = items.toSet(),
            ),
        )
    }
}