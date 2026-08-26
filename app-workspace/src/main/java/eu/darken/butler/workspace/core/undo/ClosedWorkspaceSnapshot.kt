package eu.darken.butler.workspace.core.undo

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/**
 * One member of a closed ownership unit, with everything needed to build its stand-in again.
 *
 * @param arguments captured from the live instance, so the member comes back where the user left it
 * rather than where it was created.
 * @param automaticTitle the identity the tab showed while it was open. Re-deriving it from
 * [arguments] would be a second answer to the same question, and the two disagree for types that
 * name themselves from live state.
 */
data class ClosedWorkspaceMember(
    val id: Workspace.Id,
    val type: Workspace.Type,
    val arguments: Workspace.Arguments,
    val createdAt: Instant?,
    val customTitle: String?,
    val automaticTitle: CaString,
    val automaticSubtitle: CaString?,
    val callerWorkspaceId: Workspace.Id?,
)

/**
 * The half of a stashed close that the repo owns: who was closed, where in the list, and what the
 * conflict situation looked like before the close.
 *
 * @param members root first, owners before what they own - the order [WorkspaceAction.UndoClose]
 * has to insert them in.
 * @param unitOrderIndex position of the unit's root among the unit owners, the fallback for
 * re-inserting when none of [neighbourIds] survived.
 * @param neighbourIds unit owners that surrounded the closed one, nearest first, preceding
 * neighbours before following ones. Position is re-derived from these at restore time, so a reorder
 * during the undo window puts the tab back beside its neighbours instead of at a stale index.
 * @param baselineContentHolders workspace publishing each content path of the unit ROOT at close
 * time, null when nobody did. Only a holder that is new or different since then blocks the undo -
 * content paths are explicitly non-exclusive, so a duplicate that predates the close is not a
 * conflict the user created by undoing.
 * @param baselineSingletonOccupant same for the singleton slot of the root's type.
 */
data class ClosedWorkspaceSnapshot(
    val members: List<ClosedWorkspaceMember>,
    val unitOrderIndex: Int,
    val neighbourIds: List<Workspace.Id>,
    val closeToken: Long,
    val baselineContentHolders: Map<APath<*>, Workspace.Id?>,
    val baselineSingletonOccupant: Pair<Workspace.Type, Workspace.Id?>?,
) {
    val root: ClosedWorkspaceMember get() = members.first()
    val memberIds: Set<Workspace.Id> get() = members.mapTo(mutableSetOf()) { it.id }
}

/** Per-workspace view state one member held, as the slot registries store it. */
data class ClosedWorkspaceMemberSlots(
    val scrollPositions: Map<String, WorkspaceScrollPosition> = emptyMap(),
    val barCollapseStates: Map<String, Map<String, Float>> = emptyMap(),
    val viewPrefs: Map<String, JsonElement> = emptyMap(),
)

/**
 * Where the closed unit was on screen.
 *
 * @param paneIndex pane its root occupied, null when no capture point observed one - treated as
 * "target unavailable" rather than as pane 0, so a missed capture degrades to a background restore
 * instead of evicting whatever is there now.
 * @param focusedMemberId member that held focus, null when the unit was closed in the background
 * and the restore must not steal focus.
 */
data class ClosedWorkspacePlacement(
    val paneIndex: Int?,
    val focusedMemberId: Workspace.Id?,
)

/** A complete stash entry: identity half plus the UI half both capture points contributed. */
data class ClosedWorkspaceEntry(
    val snapshot: ClosedWorkspaceSnapshot,
    val slots: Map<Workspace.Id, ClosedWorkspaceMemberSlots>,
    val placement: ClosedWorkspacePlacement,
)

/**
 * What the UI still has to apply after the repo published the restored stand-ins.
 *
 * Handed over rather than applied by the undoing caller alone: publication is the irreversible
 * commit point, so a caller whose scope dies right after it must not be the only one that could
 * still place the tab.
 */
data class ClosedWorkspaceRestoreTicket(
    val rootId: Workspace.Id,
    val restoreToken: Long,
    val slots: Map<Workspace.Id, ClosedWorkspaceMemberSlots>,
    val placement: ClosedWorkspacePlacement,
)

/** What the undo bar shows: the tab's name, resolved to a string by the UI. */
data class ClosedWorkspaceFeedback(
    val closeToken: Long,
    val customTitle: String?,
    val automaticTitle: CaString,
)
