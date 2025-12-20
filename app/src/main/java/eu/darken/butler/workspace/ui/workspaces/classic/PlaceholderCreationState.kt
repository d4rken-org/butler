package eu.darken.butler.workspace.ui.workspaces.classic

/**
 * State machine for placeholder page workspace creation.
 *
 * State transitions:
 * ```
 * ┌──────┐  settle on   ┌──────────┐  auto/click  ┌─────────┐
 * │ Idle │ ───────────► │ Visiting │ ───────────► │Triggered│
 * └──────┘  placeholder  └──────────┘              └─────────┘
 *     ▲                       │                        │
 *     │                       │ swipe away             │ dispatch
 *     │                       ▼                        ▼
 *     │                  ┌──────┐               ┌──────────┐
 *     │◄─────────────────│ Idle │◄──────────────│ Creating │
 *     │   leave page     └──────┘  ws created   └──────────┘
 *     │                      ▲                       │
 *     │                      │ leave page            │ limit reached
 *     │                  ┌─────────┐             ┌────────┐
 *     │                  │ Blocked │◄────────────│ Failed │
 *     │                  └─────────┘  dismiss    └────────┘
 *     │                      │ click
 *     │                      ▼
 *     │                  ┌─────────┐
 *     └──────────────────│Triggered│ (retry)
 * ```
 */
sealed interface PlaceholderCreationState {
    /** Ready for next placeholder visit */
    data object Idle : PlaceholderCreationState

    /** On placeholder page, awaiting auto-trigger or click */
    data object Visiting : PlaceholderCreationState

    /** Creation action dispatched */
    data object Triggered : PlaceholderCreationState

    /** Waiting for workspace to appear or limit dialog */
    data object Creating : PlaceholderCreationState

    /** Limit reached, dialog is showing */
    data object Failed : PlaceholderCreationState

    /** Limit was reached, dialog dismissed, still on placeholder. Click to retry, or leave to reset. */
    data object Blocked : PlaceholderCreationState
}
