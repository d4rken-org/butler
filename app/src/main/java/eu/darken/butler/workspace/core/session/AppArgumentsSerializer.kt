package eu.darken.butler.workspace.core.session

import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsArguments
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.arguments.ExternalExplorerArguments
import eu.darken.butler.explorer.core.picker.ExplorerPickerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import eu.darken.butler.workspace.core.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level implementation of ArgumentsSerializer that has access to all workspace types
 */
@Singleton
class AppArgumentsSerializer @Inject constructor(
    private val json: Json,
    private val baseSerializer: ArgumentsSerializer, // Delegate to base for unknown types
) {
    private val tag = logTag("Workspace", "AppArgumentsSerializer")

    /**
     * Serialize workspace arguments to JSON with full type knowledge
     */
    fun serialize(type: Workspace.Type, arguments: Workspace.Arguments?): JsonElement? {
        if (arguments == null) return null

        return try {
            val serializable = when (type) {
                Workspace.Type.EXPLORER -> when (arguments) {
                    is ExplorerWorkspace.Arguments -> SerializableExplorerArguments(
                        startPath = arguments.startPath?.path
                    )
                    is ExternalExplorerArguments -> SerializableExplorerArguments(
                        startPath = arguments.startPath?.path
                    )
                    is ExplorerPickerArguments -> SerializableExplorerPickerArguments(
                        startPath = arguments.startPath?.path,
                        selection = arguments.selection.toString(),
                        callerWorkspaceId = arguments.callerWorkspaceId?.toString()
                    )
                    else -> {
                        log(tag, WARN) { "Unknown Explorer arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }

                Workspace.Type.SEARCHER -> when (arguments) {
                    is SearcherWorkspace.Arguments -> SerializableSearcherArguments(
                        startTargets = arguments.startTargets?.toString()
                    )
                    else -> {
                        log(tag, WARN) { "Unknown Searcher arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }

                Workspace.Type.EDITOR -> when (arguments) {
                    is EditorWorkspace.Arguments -> SerializableEditorArguments(
                        filePath = arguments.filePath?.path,
                        goToLine = arguments.goToLine
                    )
                    else -> {
                        log(tag, WARN) { "Unknown Editor arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }

                Workspace.Type.APPS -> when (arguments) {
                    is AppsWorkspace.Arguments -> SerializableAppsArguments()
                    else -> {
                        log(tag, WARN) { "Unknown Apps arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }

                Workspace.Type.APP_DETAILS -> when (arguments) {
                    is AppDetailsArguments -> SerializableAppDetailsArguments(
                        packageName = arguments.packageName
                    )
                    else -> {
                        log(tag, WARN) { "Unknown AppDetails arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }

                Workspace.Type.TEMPLATES -> when (arguments) {
                    is TemplatesWorkspace.Arguments -> SerializableTemplatesArguments(
                        placeholder = arguments.placeholder
                    )
                    else -> {
                        log(tag, WARN) { "Unknown Templates arguments type: ${arguments::class.simpleName}" }
                        return baseSerializer.serialize(type, arguments)
                    }
                }
            }

            json.parseToJsonElement(json.encodeToString(serializable))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to serialize arguments for $type: ${e.asLog()}" }
            baseSerializer.serialize(type, arguments)
        }
    }

    /**
     * Deserialize workspace arguments from JSON with full type knowledge
     */
    fun deserialize(type: Workspace.Type, element: JsonElement): Workspace.Arguments? {
        return try {
            when (type) {
                Workspace.Type.EXPLORER -> {
                    // Try picker arguments first (has callerWorkspaceId)
                    try {
                        val jsonStr = json.encodeToString(element)
                        val pickArgs = json.decodeFromString<SerializableExplorerPickerArguments>(jsonStr)
                        if (pickArgs.callerWorkspaceId != null) {
                            return ExplorerPickerArguments(
                                startPath = pickArgs.startPath?.let { LocalPath.build(it) },
                                selection = parsePickerSelection(pickArgs.selection),
                                callerWorkspaceId = Workspace.Id()
                            )
                        }
                    } catch (_: Exception) {
                        // Not picker arguments, try regular
                    }

                    val jsonStr = json.encodeToString(element)
                    val args = json.decodeFromString<SerializableExplorerArguments>(jsonStr)
                    ExplorerWorkspace.Arguments(
                        startPath = args.startPath?.let { LocalPath.build(it) }
                    )
                }

                Workspace.Type.SEARCHER -> {
                    val jsonStr = json.encodeToString(element)
                    val args = json.decodeFromString<SerializableSearcherArguments>(jsonStr)
                    SearcherWorkspace.Arguments(
                        startTargets = null
                    )
                }

                Workspace.Type.EDITOR -> {
                    val jsonStr = json.encodeToString(element)
                    val args = json.decodeFromString<SerializableEditorArguments>(jsonStr)
                    EditorWorkspace.Arguments(
                        filePath = args.filePath?.let { LocalPath.build(it) },
                        goToLine = args.goToLine
                    )
                }

                Workspace.Type.APPS -> {
                    AppsWorkspace.Arguments()
                }

                Workspace.Type.APP_DETAILS -> {
                    val jsonStr = json.encodeToString(element)
                    val args = json.decodeFromString<SerializableAppDetailsArguments>(jsonStr)
                    AppDetailsArguments(
                        packageName = args.packageName
                    )
                }

                Workspace.Type.TEMPLATES -> {
                    val jsonStr = json.encodeToString(element)
                    val args = json.decodeFromString<SerializableTemplatesArguments>(jsonStr)
                    TemplatesWorkspace.Arguments(
                        placeholder = args.placeholder
                    )
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to deserialize arguments for $type: ${e.asLog()}" }
            baseSerializer.deserialize(type, element)
        }
    }

    private fun parsePickerSelection(selection: String?): PickerConfig.Selection {
        return when (selection) {
            "DirectorySingle" -> PickerConfig.Selection.DirectorySingle
            "DirectoryMulti" -> PickerConfig.Selection.DirectoryMulti
            "FileSingle" -> PickerConfig.Selection.FileSingle
            "FileMulti" -> PickerConfig.Selection.FileMulti
            "MixedMulti" -> PickerConfig.Selection.MixedMulti
            else -> PickerConfig.Selection.DirectorySingle
        }
    }
}

// Serializable data classes for each workspace type

@Serializable
private data class SerializableExplorerArguments(
    val startPath: String? = null,
)

@Serializable
private data class SerializableExplorerPickerArguments(
    val startPath: String? = null,
    val selection: String? = null,
    val callerWorkspaceId: String? = null,
)

@Serializable
private data class SerializableSearcherArguments(
    val startTargets: String? = null,
)

@Serializable
private data class SerializableEditorArguments(
    val filePath: String? = null,
    val goToLine: Int? = null,
)

@Serializable
private data class SerializableAppsArguments(
    val dummy: String? = null // No arguments needed
)

@Serializable
private data class SerializableAppDetailsArguments(
    val packageName: String,
)

@Serializable
private data class SerializableTemplatesArguments(
    val placeholder: String = "",
)