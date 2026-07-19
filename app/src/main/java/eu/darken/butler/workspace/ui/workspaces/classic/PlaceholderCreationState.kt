package eu.darken.butler.workspace.ui.workspaces.classic

/**
 * State machine for placeholder page workspace creation, owned by
 * [PlaceholderCreationController].
 *
 * State transitions:
 * ```
 * ┌──────┐ user drag settles ┌──────────┐  dwell/click  ┌──────────┐
 * │ Idle │ ────────────────► │ Visiting │ ────────────► │ Creating │
 * └──────┘  on placeholder   └──────────┘               └──────────┘
 *     ▲                           │                          │
 *     │  leave page / list        │                          │ ws created
 *     │  change / overlay /       │                          ▼
 *     │  setting off              │                      ┌──────┐
 *     │◄──────────────────────────┘                      │ Idle │
 *     │                                                  └──────┘
 *     │                                                      │ limit reached
 *     │                 ┌─────────┐        dismiss       ┌────────┐
 *     │◄────────────────│ Blocked │◄─────────────────────│ Failed │
 *     │   leave page    └─────────┘  (still on page)     └────────┘
 *     │                      │ click
 *     │                      ▼
 *     └────────────────┌──────────┐
 *                      │ Creating │ (retry)
 *                      └──────────┘
 * ```
 *
 * Entering [Visiting] requires a real user drag gesture — a list mutation that
 * strands the pager on the placeholder page never arms creation.
 */
sealed interface PlaceholderCreationState {
    /** Ready for next placeholder visit */
    data object Idle : PlaceholderCreationState

    /** On placeholder page via user gesture, awaiting dwell auto-trigger or click */
    data object Visiting : PlaceholderCreationState

    /** Creation dispatched, waiting for the workspace to appear or the limit dialog */
    data object Creating : PlaceholderCreationState

    /** Limit reached, dialog is showing */
    data object Failed : PlaceholderCreationState

    /** Limit was reached, dialog dismissed, still on placeholder. Click to retry, or leave to reset. */
    data object Blocked : PlaceholderCreationState
}
