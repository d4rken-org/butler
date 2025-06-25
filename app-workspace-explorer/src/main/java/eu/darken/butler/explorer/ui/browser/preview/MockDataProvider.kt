package eu.darken.butler.explorer.ui.browser.preview

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.explorer.ui.browser.FileItem
import java.time.Instant

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
    
    fun createMockDirectory(name: String = "Documents", childCount: Int? = 5): FileItem.Directory {
        return FileItem.Directory(
            lookup = createMockLookup(name, "/home/user/$name", 0L, FileType.DIRECTORY),
            mimeType = "inode/directory",
            isSelected = false,
            childCount = childCount
        )
    }
    
    fun createMockImageFile(name: String = "vacation.jpg", dimensions: String? = "1920x1080"): FileItem.ImageFile {
        return FileItem.ImageFile(
            lookup = createMockLookup(name, "/home/user/Pictures/$name", 2_048_576L),
            mimeType = "image/jpeg",
            isSelected = false,
            dimensions = dimensions
        )
    }
    
    fun createMockMediaFile(
        name: String = "song.mp3", 
        isVideo: Boolean = false,
        duration: String? = "3:45",
        resolution: String? = null
    ): FileItem.MediaFile {
        val mimeType = if (isVideo) "video/mp4" else "audio/mpeg"
        val actualResolution = if (isVideo) resolution ?: "1920x1080" else null
        
        return FileItem.MediaFile(
            lookup = createMockLookup(name, "/home/user/Media/$name", 5_242_880L),
            mimeType = mimeType,
            isSelected = false,
            duration = duration,
            resolution = actualResolution
        )
    }
    
    fun createMockApkFile(
        name: String = "app.apk",
        packageName: String? = "com.example.app",
        versionName: String? = "1.2.3",
        appName: String? = "Example App"
    ): FileItem.ApkFile {
        return FileItem.ApkFile(
            lookup = createMockLookup(name, "/home/user/Downloads/$name", 15_728_640L),
            mimeType = "application/vnd.android.package-archive",
            isSelected = false,
            packageName = packageName,
            versionName = versionName,
            appName = appName
        )
    }
    
    fun createMockArchiveFile(
        name: String = "archive.zip",
        entryCount: Int? = 25,
        compressionRatio: Float? = 0.65f
    ): FileItem.ArchiveFile {
        return FileItem.ArchiveFile(
            lookup = createMockLookup(name, "/home/user/Downloads/$name", 10_485_760L),
            mimeType = "application/zip",
            isSelected = false,
            compressionRatio = compressionRatio,
            entryCount = entryCount
        )
    }
    
    fun createMockDocumentFile(
        name: String = "document.pdf",
        pageCount: Int? = 42,
        author: String? = "John Doe"
    ): FileItem.DocumentFile {
        return FileItem.DocumentFile(
            lookup = createMockLookup(name, "/home/user/Documents/$name", 1_048_576L),
            mimeType = "application/pdf",
            isSelected = false,
            pageCount = pageCount,
            author = author
        )
    }
    
    fun createMockSymbolicLink(
        name: String = "shortcut",
        targetPath: String? = "/home/user/target/file.txt",
        isBroken: Boolean = false
    ): FileItem.SymbolicLink {
        val target = targetPath?.let { RawPath.build(it) }
        return FileItem.SymbolicLink(
            lookup = createMockLookup(name, "/home/user/$name", 0L, FileType.SYMBOLIC_LINK, target = target),
            mimeType = "inode/symlink",
            isSelected = false,
            targetPath = targetPath,
            isBroken = isBroken
        )
    }
    
    fun createMockRegularFile(name: String = "readme.txt"): FileItem.RegularFile {
        return FileItem.RegularFile(
            lookup = createMockLookup(name, "/home/user/$name", 4_096L),
            mimeType = "text/plain",
            isSelected = false
        )
    }
    
    fun createAllFileTypes(): List<FileItem> {
        return listOf(
            createMockDirectory(),
            createMockImageFile(),
            createMockMediaFile(isVideo = false),
            createMockMediaFile("video.mp4", isVideo = true),
            createMockApkFile(),
            createMockArchiveFile(),
            createMockDocumentFile(),
            createMockSymbolicLink(),
            createMockSymbolicLink("broken_link", isBroken = true),
            createMockRegularFile()
        )
    }
}