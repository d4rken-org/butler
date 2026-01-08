package eu.darken.butler.developer.core.operations

import eu.darken.butler.common.files.LocalPath

sealed interface DeveloperCommand {

    data class GenerateLargeFiles(
        val basePath: LocalPath,
        val folderName: String = "aButlerLargeFiles",
    ) : DeveloperCommand

    data class GenerateNestedStructure(
        val basePath: LocalPath,
        val folderName: String = "aButlerNestedData",
        val depth: Int = 6,
        val foldersPerLevel: Int = 3,
        val filesPerFolder: Int = 3,
    ) : DeveloperCommand

    data class GenerateTextFiles(
        val basePath: LocalPath,
        val folderName: String = "aButlerTextFiles",
    ) : DeveloperCommand
}
