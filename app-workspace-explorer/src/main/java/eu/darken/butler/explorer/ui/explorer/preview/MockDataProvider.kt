package eu.darken.butler.explorer.ui.explorer.preview

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.explorer.core.engine.ExplorerItem
import kotlin.time.Instant

object MockDataProvider {
    
    private fun createMockLookup(
        name: String,
        path: String = "/test/$name",
        size: Long = 1024L,
        fileType: FileType = FileType.FILE,
        modifiedAt: Instant = Instant.parse("2023-10-15T10:30:00Z"),
        target: RawPath? = null
    ): APathLookup<*> {
        // Create a mock implementation for previews
        return object : APathLookup<RawPath> {
            override val lookedUp: RawPath = RawPath.build(path)
            override val size: Long = size
            override val fileType: FileType = fileType
            override val modifiedAt: Instant = modifiedAt
            override val target: RawPath? = target
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
        val target = targetPath?.let { RawPath.build(it) }
        return ExplorerItem.SymbolicLink(
            lookup = createMockLookup(name, "/home/user/$name", 0L, FileType.SYMBOLIC_LINK, target = target),
            mimeType = "inode/symlink",
            targetPath = targetPath,
            isBroken = isBroken
        )
    }

    fun createMockRegularFile(name: String = "readme.txt"): ExplorerItem.RegularFile {
        return ExplorerItem.RegularFile(
            lookup = createMockLookup(name, "/home/user/$name", 4_096L),
            mimeType = "text/plain",
        ).withExtendedData(
            ownership = Ownership(
                userId = 123,
                groupId = 456,
                userName = "aUser",
                groupName = "aGroup",
            ),
            permissions = Permissions(mode = 755)
        )
    }

    fun createAllFileTypes(): List<ExplorerItem.PathItem> {
        return listOf(
            createMockDirectory(),
            createMockSymbolicLink(),
            createMockSymbolicLink("broken_link", isBroken = true),
            createMockRegularFile()
        )
    }
}