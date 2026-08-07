package eu.darken.butler.workspace.core

/**
 * Ownership topology of the open workspaces: who owns whom via [Workspace.Info.callerWorkspaceId].
 *
 * One shared walk for everything that has to agree on it - which modal chains the screen renders,
 * which workspaces auto-pause may touch, what the tab manager offers, and which id a pause lease is
 * keyed on. Those four disagreeing is what let a rendered modal be classified as idle.
 *
 * Caller relationships are not validated at creation time, so every walk here is guarded: a cycle or
 * a caller id that no longer resolves yields null/no chain instead of looping or inventing an owner.
 * Cheap to build (two maps) and immutable, so callers construct one per snapshot.
 */
class WorkspaceStacks(private val infos: List<Workspace.Info>) {

    private val byId = infos.associateBy { it.id }

    private val childrenOf: Map<Workspace.Id, List<Workspace.Info>> = infos
        .mapNotNull { info -> info.callerWorkspaceId?.let { it to info } }
        .groupBy({ it.first }, { it.second })

    /**
     * The workspace [id] ultimately belongs to: itself when it is a tab, otherwise the tab its
     * caller chain terminates at. Null when [id] is unknown, its chain runs into a caller that no
     * longer exists, or the chain is cyclic.
     */
    fun rootOf(id: Workspace.Id): Workspace.Info? {
        var current = byId[id] ?: return null
        val visited = mutableSetOf<Workspace.Id>()
        while (current.isSubWorkspace) {
            if (!visited.add(current.id)) return null
            current = current.callerWorkspaceId?.let { byId[it] } ?: return null
        }
        return current
    }

    /**
     * The whole ownership unit [id] belongs to: its root first, then every descendant
     * breadth-first, so an owner always precedes anything it owns. Null when [rootOf] cannot
     * resolve, i.e. the unit has no valid owner to act on.
     */
    fun unitOf(id: Workspace.Id): List<Workspace.Info>? {
        val root = rootOf(id) ?: return null
        val members = mutableListOf(root)
        val seen = mutableSetOf(root.id)
        var index = 0
        while (index < members.size) {
            childrenOf[members[index++].id].orEmpty().forEach { child ->
                if (seen.add(child.id)) members += child
            }
        }
        return members
    }

    /**
     * The workspaces whose ownership cannot be resolved - a caller id naming nothing, or a cycle -
     * grouped into connected components over the caller relation and keyed by the member that comes
     * first in workspace order. Nothing validates caller ids at creation time, so these are
     * reachable; dropping them would leave workspaces open that no surface can list or close, while
     * listing each separately would offer several cards for what one close already takes down.
     *
     * The set is closed under the relation: a descendant of an unresolvable workspace runs into the
     * same dead end, and an ancestor that resolved would make its descendants resolve too.
     */
    val recoveryUnits: Map<Workspace.Id, List<Workspace.Info>> by lazy {
        val unresolvable = infos.filter { it.isSubWorkspace && rootOf(it.id) == null }
        val unresolvableIds = unresolvable.mapTo(mutableSetOf()) { it.id }
        val order = infos.withIndex().associate { (index, info) -> info.id to index }

        val assigned = mutableSetOf<Workspace.Id>()
        val units = mutableMapOf<Workspace.Id, List<Workspace.Info>>()

        unresolvable.forEach { seed ->
            if (!assigned.add(seed.id)) return@forEach

            val component = mutableListOf(seed)
            var index = 0
            while (index < component.size) {
                val current = component[index++]
                val neighbours = childrenOf[current.id].orEmpty() +
                    listOfNotNull(current.callerWorkspaceId?.let { byId[it] })
                neighbours.forEach { neighbour ->
                    if (neighbour.id in unresolvableIds && assigned.add(neighbour.id)) component += neighbour
                }
            }

            val members = component.sortedBy { order.getValue(it.id) }
            units[members.first().id] = members
        }

        units
    }

    private val recoveryOwnerById: Map<Workspace.Id, Workspace.Id> by lazy {
        recoveryUnits.flatMap { (owner, members) -> members.map { it.id to owner } }.toMap()
    }

    /**
     * The unit owner [id] is listed under: its ownership root, or - when ownership cannot be
     * resolved - the representative of its recovery unit. Falls back to [id] for an id this snapshot
     * never saw.
     */
    fun ownerOf(id: Workspace.Id): Workspace.Id = rootOf(id)?.id ?: recoveryOwnerById[id] ?: id

    /** One entry per unit, in workspace order: every tab, plus one representative per recovery unit. */
    val unitOwners: List<Workspace.Info> by lazy {
        infos.filter { !it.isSubWorkspace || recoveryUnits.containsKey(it.id) }
    }

    /**
     * Every modal chain currently open, one entry per chain leaf, fully validated.
     *
     * A chain is only kept when walking its callers upward terminates at a workspace that exists and
     * is not itself a sub-workspace. Anything else - a caller id that no longer resolves, a cycle, or
     * a leaf whose ancestry runs into one - is dropped rather than rendered: a modal whose owning tab
     * cannot be named has no pane to belong to, and surfacing it would put an undismissable overlay
     * over an unrelated tab.
     */
    val chains: List<WorkspaceStackChain> by lazy {
        val callerIds = infos.mapNotNull { it.callerWorkspaceId }.toSet()

        infos.withIndex().mapNotNull { (order, leaf) ->
            if (!leaf.isSubWorkspace || leaf.id in callerIds) return@mapNotNull null

            val ancestry = mutableListOf<Workspace.Info>()
            val visited = mutableSetOf<Workspace.Id>()
            var current: Workspace.Info = leaf
            var root: Workspace.Info? = null

            while (visited.add(current.id)) {
                ancestry += current
                val caller = current.callerWorkspaceId?.let { byId[it] } ?: break
                if (!caller.isSubWorkspace) {
                    root = caller
                    break
                }
                current = caller
            }

            root?.let {
                WorkspaceStackChain(root = it, modals = ancestry.reversed(), order = order)
            }
        }
    }

    /**
     * The chains actually on screen for the given focus, split by how they are rendered.
     *
     * The layout is deliberately not an input: a pane-local chain stacks inside its own tab at any
     * pane count - on a phone that tab is the pager page it belongs to.
     *
     * Selection is not an input either: a full-screen chain covers every pane regardless of what is
     * selected (see [preferred]), and a pane-local chain only shows when its own tab is selected,
     * which its root id already tells the caller.
     */
    fun renderedChains(focusedId: Workspace.Id?): RenderedWorkspaceStacks {
        val (fullScreen, paneLocal) = chains.partition { it.isFullScreen }
        return RenderedWorkspaceStacks(
            fullScreen = fullScreen.preferred(focusedId),
            paneLocal = paneLocal
                .groupBy { it.root.id }
                .mapNotNull { (rootId, candidates) -> candidates.preferred(focusedId)?.let { rootId to it } }
                .toMap(),
        )
    }

    /**
     * The chain currently on top of each root, keyed by root id: full-screen siblings first (they
     * cover their pane-local siblings on that tab), then the one focus points into, else the newest.
     * Roots with no modal have no entry.
     *
     * Deliberately different from [renderedChains], which collapses all full-screen chains to a
     * single global winner: the tab manager lists tabs independently, so each root's own top is the
     * right answer for that tab's card.
     */
    fun topChainByRoot(focusedId: Workspace.Id?): Map<Workspace.Id, WorkspaceStackChain> = chains
        .groupBy { it.root.id }
        .mapNotNull { (rootId, candidates) ->
            // renderedChains partitions before picking, so grouping by root alone would let a card
            // name a pane-local leaf while a full-screen sibling is what actually covers that tab.
            val pool = candidates.filter { it.isFullScreen }.ifEmpty { candidates }
            pool.preferred(focusedId)?.let { rootId to it }
        }
        .toMap()

    /**
     * The chain the user is working in: the one focus points into, else the newest.
     *
     * `launchPicker` never moves the global focus, so focus can sit on any member of a chain or on
     * its owning tab - all of them identify the same branch. When it points at none of them the
     * newest chain still renders, which is why "visible" can never be derived from focus alone.
     */
    private fun List<WorkspaceStackChain>.preferred(focusedId: Workspace.Id?): WorkspaceStackChain? {
        if (focusedId == null) return maxByOrNull { it.order }
        return filter { chain -> focusedId in chain.memberIds }.maxByOrNull { it.order }
            ?: maxByOrNull { it.order }
    }
}

/**
 * One validated ownership chain: the tab it belongs to, and the modals stacked on that tab.
 *
 * @param modals nearest-root-first, depth 1..N. Never empty.
 * @param order index of the chain's leaf in the workspace list, for newest-wins tie-breaking.
 */
data class WorkspaceStackChain(
    val root: Workspace.Info,
    val modals: List<Workspace.Info>,
    val order: Int,
) {
    val leaf: Workspace.Info get() = modals.last()

    /** The root plus every modal of this chain. */
    val memberIds: Set<Workspace.Id> get() = modals.mapTo(mutableSetOf(root.id)) { it.id }

    /**
     * True when this chain renders as a Dialog covering all panes: any member asks for FULL_SCREEN,
     * so a pane-local descendant of a full-screen parent still renders full-screen.
     *
     * Independent of the pane count on purpose: a purely pane-local chain stacks inside its owning
     * tab everywhere, and on a single-pane layout that tab is the pager page it belongs to.
     */
    val isFullScreen: Boolean
        get() = modals.any { it.modalPresentation == Workspace.ModalPresentationMode.FULL_SCREEN }
}

/**
 * The modal chains a given layout puts on screen. [fullScreen] and [paneLocal] are mutually
 * exclusive: every resolved chain lands in exactly one of them, so none is rendered twice.
 *
 * @param paneLocal at most one chain per owning tab - two sibling branches cannot both be on top, so
 * the focused (else newest) branch wins and the other stays composed-out.
 */
data class RenderedWorkspaceStacks(
    val fullScreen: WorkspaceStackChain?,
    val paneLocal: Map<Workspace.Id, WorkspaceStackChain>,
)
