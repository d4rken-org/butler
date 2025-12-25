package eu.darken.butler.searcher.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Audiotrack
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.R
import kotlin.time.Duration.Companion.days

sealed class SearchTemplate(
    val id: String,
    val name: CaString,
    val description: CaString,
    val icon: ImageVector,
) {
    abstract fun createQuery(currentTargets: List<SearchTarget>): SearchQuery

    data object LargeFiles : SearchTemplate(
        id = "large_files",
        name = R.string.searcher_template_large_files.toCaString(),
        description = R.string.searcher_template_large_files_desc.toCaString(),
        icon = Icons.TwoTone.Storage,
    ) {
        private const val SIZE_100_MB = 100L * 1024 * 1024

        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = MATCH_ALL_QUERY,
            filter = SearchFilter(
                conditions = listOf(
                    FilterCondition.Type(FileType.FILE),
                    FilterCondition.Size(FilterComparator.GTE, SIZE_100_MB),
                ),
            ),
        )
    }

    data object RecentFiles : SearchTemplate(
        id = "recent_files",
        name = R.string.searcher_template_recent_files.toCaString(),
        description = R.string.searcher_template_recent_files_desc.toCaString(),
        icon = Icons.TwoTone.Schedule,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>): SearchQuery {
            val sevenDaysAgo = kotlin.time.Clock.System.now() - 7.days
            return SearchQuery(
                targets = currentTargets,
                filenameQuery = MATCH_ALL_QUERY,
                filter = SearchFilter(
                    conditions = listOf(
                        FilterCondition.ModifiedDate(FilterComparator.GT, sevenDaysAgo),
                    ),
                ),
            )
        }
    }

    data object OldFiles : SearchTemplate(
        id = "old_files",
        name = R.string.searcher_template_old_files.toCaString(),
        description = R.string.searcher_template_old_files_desc.toCaString(),
        icon = Icons.TwoTone.History,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>): SearchQuery {
            val oneYearAgo = kotlin.time.Clock.System.now() - 365.days
            return SearchQuery(
                targets = currentTargets,
                filenameQuery = MATCH_ALL_QUERY,
                filter = SearchFilter(
                    conditions = listOf(
                        FilterCondition.ModifiedDate(FilterComparator.LT, oneYearAgo),
                    ),
                ),
            )
        }
    }

    data object Images : SearchTemplate(
        id = "images",
        name = R.string.searcher_template_images.toCaString(),
        description = R.string.searcher_template_images_desc.toCaString(),
        icon = Icons.TwoTone.Image,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.(jpg|jpeg|png|gif|webp|bmp|heic)$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    data object Videos : SearchTemplate(
        id = "videos",
        name = R.string.searcher_template_videos.toCaString(),
        description = R.string.searcher_template_videos_desc.toCaString(),
        icon = Icons.TwoTone.Videocam,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.(mp4|mkv|avi|mov|webm|3gp)$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    data object Audio : SearchTemplate(
        id = "audio",
        name = R.string.searcher_template_audio.toCaString(),
        description = R.string.searcher_template_audio_desc.toCaString(),
        icon = Icons.TwoTone.Audiotrack,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.(mp3|wav|flac|ogg|m4a|aac)$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    data object Documents : SearchTemplate(
        id = "documents",
        name = R.string.searcher_template_documents.toCaString(),
        description = R.string.searcher_template_documents_desc.toCaString(),
        icon = Icons.TwoTone.Description,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|txt)$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    data object Archives : SearchTemplate(
        id = "archives",
        name = R.string.searcher_template_archives.toCaString(),
        description = R.string.searcher_template_archives_desc.toCaString(),
        icon = Icons.TwoTone.FolderZip,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.(zip|rar|7z|tar|gz|bz2)$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    data object APKs : SearchTemplate(
        id = "apks",
        name = R.string.searcher_template_apks.toCaString(),
        description = R.string.searcher_template_apks_desc.toCaString(),
        icon = Icons.TwoTone.PhoneAndroid,
    ) {
        override fun createQuery(currentTargets: List<SearchTarget>) = SearchQuery(
            targets = currentTargets,
            filenameQuery = FilenameQuery(
                pattern = "\\.apk$",
                useRegex = true,
                caseSensitive = false,
            ),
        )
    }

    companion object {
        /** Match-all query for filter-only templates */
        private val MATCH_ALL_QUERY = FilenameQuery(
            pattern = ".",
            useRegex = true,
            caseSensitive = false,
        )

        val builtIn: List<SearchTemplate> = listOf(
            LargeFiles,
            RecentFiles,
            OldFiles,
            Images,
            Videos,
            Audio,
            Documents,
            Archives,
            APKs,
        )
    }
}
