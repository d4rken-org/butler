package eu.darken.butler.common.files.metadata

import kotlin.time.Duration

/**
 * Marker interface for file-specific metadata extracted by [MetadataExtractor].
 *
 * Metadata is embedded in specialized item types (e.g., ApkFile, ImageFile)
 * and extracted during item creation by engines (SearchEngine, BrowsingEngine).
 */
sealed interface FileMetadata

/**
 * Metadata extracted from APK (Android Package) files.
 */
data class ApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val applicationLabel: String? = null,
    val permissions: List<String> = emptyList(),
) : FileMetadata

/**
 * Metadata extracted from image files (JPEG, PNG, GIF, etc.).
 */
data class ImageMetadata(
    val width: Int,
    val height: Int,
    val format: String,  // "JPEG", "PNG", "GIF", "BMP", "WEBP"
    val exifData: Map<String, String> = emptyMap(),
) : FileMetadata

/**
 * Metadata extracted from video files.
 */
data class VideoMetadata(
    val duration: Duration,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val bitrate: Long? = null,
    val frameRate: Double? = null,
) : FileMetadata

/**
 * Metadata extracted from audio files.
 */
data class AudioMetadata(
    val duration: Duration,
    val bitrate: Long? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
) : FileMetadata
