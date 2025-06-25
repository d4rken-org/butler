package eu.darken.butler.explorer.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.graphics.vector.ImageVector

sealed interface PathSegment {
    data class Location(
        val type: LocationType,
        val name: String,
        val icon: ImageVector,
        val rootPath: String
    ) : PathSegment

    data class Directory(
        val name: String,
        val path: String
    ) : PathSegment

    data class StorageRoot(
        val type: StorageType,
        val name: String,
        val icon: ImageVector,
        val path: String,
        val requiresRoot: Boolean = false
    ) : PathSegment
}

enum class LocationType {
    HOME,
    DEVICE_INTERNAL,
    SDCARD,
    USB_STORAGE
}

enum class StorageType {
    FILESYSTEM_ROOT,
    USER_STORAGE,
    EXTERNAL_SDCARD,
    EXTERNAL_USB
}

sealed class PathValidationResult {
    data class Valid(val normalizedPath: String) : PathValidationResult()
    object NavigateToHome : PathValidationResult()
    data class Invalid(val error: String) : PathValidationResult()
}

object PathUtils {
    fun validateAndNormalizePath(inputPath: String, currentAbsolutePath: String): PathValidationResult {
        val trimmed = inputPath.trim()

        return when {
            // Empty path -> Go to Home
            trimmed.isEmpty() -> PathValidationResult.NavigateToHome

            // Just "/" -> Valid root
            trimmed == "/" -> PathValidationResult.Valid("/")

            // Normalize multiple slashes, remove trailing slash
            trimmed.startsWith("/") -> {
                val normalized = trimmed.replace(Regex("/+"), "/").removeSuffix("/")
                PathValidationResult.Valid(if (normalized.isEmpty()) "/" else normalized)
            }

            // Relative path -> Convert to absolute using current context
            else -> {
                val absolute = resolveRelativePath(currentAbsolutePath, trimmed)
                PathValidationResult.Valid(absolute)
            }
        }
    }

    private fun resolveRelativePath(currentPath: String, relativePath: String): String {
        val currentDir = if (currentPath == "/") "/" else currentPath
        return if (currentDir.endsWith("/")) {
            "$currentDir$relativePath"
        } else {
            "$currentDir/$relativePath"
        }
    }

    fun parsePath(currentPath: String): List<PathSegment> {
        return when {
            currentPath == "/" -> listOf(
                PathSegment.Location(LocationType.HOME, "Home", Icons.Default.Home, ""),
                PathSegment.StorageRoot(
                    StorageType.FILESYSTEM_ROOT,
                    "Root",
                    Icons.Default.Android,
                    "/",
                    requiresRoot = true
                )
            )

            currentPath.startsWith("/storage/emulated/0") -> {
                val remainingPath = currentPath.removePrefix("/storage/emulated/0")
                listOf(
                    PathSegment.Location(LocationType.HOME, "Home", Icons.Default.Home, ""),
                    PathSegment.Location(
                        LocationType.DEVICE_INTERNAL,
                        "Internal Storage",
                        Icons.Default.Storage,
                        "/storage/emulated/0"
                    )
                ) + parseDirectoryPath(remainingPath, "/storage/emulated/0")
            }

            currentPath.matches(Regex("^/storage/[A-F0-9]{4}-[A-F0-9]{4}.*")) -> {
                val parts = currentPath.split("/")
                val sdcardId = parts[2]
                val sdcardRoot = "/storage/$sdcardId"
                val remainingPath = currentPath.removePrefix(sdcardRoot)
                listOf(
                    PathSegment.Location(LocationType.HOME, "Home", Icons.Default.Home, ""),
                    PathSegment.Location(
                        LocationType.SDCARD,
                        "SD Card",
                        Icons.Default.SdCard,
                        sdcardRoot
                    )
                ) + parseDirectoryPath(remainingPath, sdcardRoot)
            }

            currentPath.matches(Regex("^/storage/usb\\d+.*")) -> {
                val parts = currentPath.split("/")
                val usbId = parts[2]
                val usbRoot = "/storage/$usbId"
                val remainingPath = currentPath.removePrefix(usbRoot)
                listOf(
                    PathSegment.Location(LocationType.HOME, "Home", Icons.Default.Home, ""),
                    PathSegment.Location(
                        LocationType.USB_STORAGE,
                        "USB Storage",
                        Icons.Default.Usb,
                        usbRoot
                    )
                ) + parseDirectoryPath(remainingPath, usbRoot)
            }

            else -> {
                // Generic root-based path
                listOf(
                    PathSegment.Location(LocationType.HOME, "Home", Icons.Default.Home, ""),
                    PathSegment.StorageRoot(
                        StorageType.FILESYSTEM_ROOT,
                        "Root",
                        Icons.Default.Folder,
                        "/",
                        requiresRoot = true
                    )
                ) + parseDirectoryPath(currentPath, "/")
            }
        }
    }

    private fun parseDirectoryPath(remainingPath: String, basePath: String): List<PathSegment.Directory> {
        if (remainingPath.isEmpty() || remainingPath == "/") {
            return emptyList()
        }

        val segments = remainingPath.removePrefix("/").split("/").filter { it.isNotEmpty() }
        val result = mutableListOf<PathSegment.Directory>()

        for (i in segments.indices) {
            val segmentPath = basePath + "/" + segments.take(i + 1).joinToString("/")
            result.add(PathSegment.Directory(segments[i], segmentPath))
        }

        return result
    }
}