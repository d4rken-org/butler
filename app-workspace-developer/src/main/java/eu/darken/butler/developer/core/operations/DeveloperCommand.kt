package eu.darken.butler.developer.core.operations

import eu.darken.butler.common.files.APath

sealed interface DeveloperCommand {

    data class GenerateLargeFiles(
        val basePath: APath<*>,
        val folderName: String = "aButlerLargeFiles",
    ) : DeveloperCommand

    data class GenerateNestedStructure(
        val basePath: APath<*>,
        val folderName: String = "aButlerNestedData",
        val depth: Int = 6,
        val foldersPerLevel: Int = 3,
        val filesPerFolder: Int = 3,
    ) : DeveloperCommand

    data class GenerateTextFiles(
        val basePath: APath<*>,
        val folderName: String = "aButlerTextFiles",
    ) : DeveloperCommand

    data class DeleteTestData(
        val basePath: APath<*>,
        val deleteLargeFiles: Boolean = false,
        val deleteNestedStructure: Boolean = false,
        val deleteTextFiles: Boolean = false,
    ) : DeveloperCommand
}
