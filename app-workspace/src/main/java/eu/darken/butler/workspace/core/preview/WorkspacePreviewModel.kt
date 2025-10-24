package eu.darken.butler.workspace.core.preview

import android.os.Parcelable
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize

/**
 * Model for Coil to load workspace preview images.
 *
 * This data class is used as the model parameter for Coil's AsyncImage.
 * A custom Coil Fetcher will resolve this to the cached preview PNG file.
 *
 * Example usage:
 * ```
 * AsyncImage(
 *     model = WorkspacePreviewModel(workspaceId),
 *     contentDescription = "Workspace preview"
 * )
 * ```
 */
@Parcelize
data class WorkspacePreviewModel(
    val workspaceId: Workspace.Id,
) : Parcelable
