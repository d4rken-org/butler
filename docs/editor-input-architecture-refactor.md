# Editor input architecture — proposed refactor

> **Status: proposal, not implemented.** This describes a design the editor does *not* currently
> have. It came out of the review of the Enter-at-end-of-document fix
> (`fix/editor-enter-and-toolbar-polish`), where eight review findings across three fix rounds all
> turned out to be symptoms of the same two structural gaps. Nothing here is a description of how
> the code works today.

## Why

One change to the editor's edit path produced this sequence of independent review findings:

1. A field/engine divergence recovery whose gate could never fire in a realistic case.
2. A confirmed large-edit dialog mutating outside the ordered consumer.
3. Cut bypassing the edit queue entirely.
4. Clipboard operations joining the queue only after their content had been retrieved.
5. Keystrokes queued during a paste carrying positions measured before it.
6. A busy flag the UI could miss entirely on a fast operation.
7. A timeout that cannot interrupt blocking I/O, so the starvation guard it provided did not work.
8. Cut deleting a different selection than the one it copied.

Each fix was a faithful implementation of a concrete correction, and each surfaced the next problem.
That is the signature of a missing abstraction rather than eight unrelated bugs.

## The two structural gaps

**1. A speculative replica with no defined lineage.** `LazyTextEditor` drives IME input through a
hidden 1.dp `BasicTextField`. That field holds only the visible window, and it applies each edit to
itself *optimistically* before dispatching it. This replica is not an accident and cannot be removed:
Android IMEs require a stable editable buffer, composition range and selection to compose into, so a
field that is reset to the engine's echo after every keystroke breaks CJK input, autocorrect and
predictive text. The defect is that its edits are dispatched as ordinary engine coordinates —
`(line, column)` with no indication of what document state they were computed against — so the engine
cannot tell a current edit from a stale one. Today the gap is papered over by `isUserEditing`,
`authorityEcho`, `contentsConverged` heuristics and an out-of-band resync signal.

**2. Late-bound mutations.** Most engine commands resolve their target at *execution* time rather
than when the user triggered them: `deleteSelection` and the action-bar delete read
`_selectionRange.value`, `deleteAtCursor` and `deleteForward` read `_cursorPosition.value`, and cut's
deletion did the same. Ordering such commands does not make them correct — it only changes which
stale state they read. Finding 8 above is exactly that: putting cut's delete on an ordered queue made
its ordering correct and its target wrong.

Note what is *not* the problem: serialization itself. The damaging combination was partial
serialization, late-bound targets, and unbounded external work (clipboard and file reads) running
inside the serialized consumer.

## The primitive already exists

`DocumentBuffer.replaceMatches(replacements, expectedVersion)` — used by search-and-replace — already
implements the required contract on one write path:

- it compares the caller's `expectedVersion` against `structuralVersion` and fails with
  `StaleMatchException` if the document moved;
- it then re-reads each target's `oldText` from the live buffer;
- both checks happen before the first mutation, all-or-nothing.

`structuralVersion` is bumped on every mutation and published as `structuralVersionFlow`. The typing
path has none of this: `replaceText` takes bare `(line, column)` and silently clamps an out-of-range
column, so a stale edit lands quietly instead of being rejected.

The refactor is largely a matter of generalising the contract one write path already honours.

## Target design

### Verified document transactions

```kotlin
data class DocumentToken(val engineEpoch: Uuid, val structuralVersion: Long)

data class VerifiedPatch(
    val range: TextRange,
    val expectedOldText: String?,   // null for oversized ranges that must not be materialized
    val replacement: String,
)

data class MutationRequest(
    val token: DocumentToken,
    val patches: List<VerifiedPatch>,
    val selectionAfter: SelectionSpec,
    val undoPolicy: UndoPolicy,
)

sealed interface MutationResult {
    data class Applied(val token: DocumentToken, val snapshot: EditorSnapshot) : MutationResult
    data class Conflict(val snapshot: EditorSnapshot) : MutationResult
    data class Failed(val error: Throwable) : MutationResult
}
```

Requirements:

- `engineEpoch` exists because `structuralVersion` restarts when the buffer is replaced; without it a
  request queued for one document can be accepted against another after a reload or file switch.
- The token, window text, window mapping, cursor and selection must be captured **atomically** — the
  field must be able to say "I computed this against exactly that state".
- Treat the version as an opaque token. A single logical replacement can bump it more than once, so
  clients must never predict the next value.
- Bounded edits verify `expectedOldText`. Oversized deletes deliberately avoid materializing their
  target, so they carry token plus explicit range instead.
- Mutation, cursor/selection update and returned snapshot are one engine transaction.
- `Conflict` is an expected synchronization outcome, not an error: it must not raise an error banner.

### An input session for the IME replica

Versioning alone breaks ordinary typing. The second keystroke of a burst is computed against the
field's speculative post-first state, not against any version the engine has reached yet, so a naive
document-version check would reject every second keystroke and drop characters.

The fix is a session that **chains on acknowledgements** rather than predicting versions:

1. `LazyTextEditor` opens an `EditorInputSession` from an atomic
   `InputSnapshot(token, windowMapping, text, selection)`.
2. Each `onValueChange` produces
   `FieldDelta(sessionId, sequence, predecessorSequence, range, oldText, newText, selectionAfter)`.
3. The field keeps its new `TextFieldValue` immediately, composition included.
4. The session has **one delta in flight at a time**; later deltas wait in its local FIFO.
5. When a delta is applied, the engine returns the resulting `DocumentToken`, and the session submits
   the next delta against *that* token. No predicted replica version is needed.
6. If a foreign mutation moves the token in between, the next delta comes back `Conflict`; the
   session invalidates that generation, discards its descendants, and rebuilds from the authoritative
   snapshot in the conflict result.

```text
Session S starts at token V40
IME emits S:1, then S:2 immediately
apply S:1 against V40 -> Applied(V41)
apply S:2 against V41 -> Applied(V42)

but if a paste produces V42 first, S:2 conflicts and S's generation resets
```

Sequence numbers still matter for correlating acknowledgements and dropping descendants of a
conflicted generation.

Keep the input window pinned while composition or unacknowledged deltas exist. Display virtualization
can continue independently.

### What the session protocol deletes

| Today | Replaced by |
|---|---|
| `contentsConverged` heuristics | `Applied(session, sequence)` |
| `_editResyncSignal` | `Conflict(snapshot)` |
| `isUserEditing` / `authorityEcho` | session generation state |
| `latestReplaceRevision` / `resyncPending` | acknowledgement chaining |
| column-representability check | `expectedOldText` verification |

A further benefit: an acknowledged own-edit never rebuilds the field, so IME composition survives.
Today `computeFieldSelectionSync` rebuilds mid-composition by design, which can disturb it.

### Async operations become prepare / effect / commit

- **Cut** — engine returns `PreparedCut(token, explicitRange, text)`; the controller writes to the
  clipboard outside the engine; it then submits
  `VerifiedPatch(range, expectedOldText = text, replacement = "")`. A moved document yields
  `Conflict` and deletes nothing.
- **Paste** — retrieve content outside the mutation path, then capture an insertion snapshot and
  submit an explicit insertion transaction at that token and offset.
- **Oversized confirmation** — prepare an immutable `MutationRequest` and store *that* in dialog
  state, not a suspending lambda. On confirm, submit it; a stale token yields `Conflict` and never
  mutates a newer document.
- **Undo / redo** — these are legitimately *semantic*, not spatial: "undo the latest committed
  transaction at this point in the stream". They are submitted as intents and execute in mailbox
  order, returning the resulting token and snapshot.

### What ordering survives

Versioning *detects* reordering but does not *preserve intent*: if keystroke 2 reaches the engine
before keystroke 1, rejecting it is safer than corrupting the document but still loses input. So keep

- the narrow FIFO inside `EditorInputSession`, and
- optionally an engine mutation actor for already-prepared, data-only transactions and semantic
  operations.

That actor must never contain clipboard or file retrieval, dialog waits, arbitrary
`suspend () -> Unit` closures, or commands that discover their target by reading mutable cursor or
selection state.

## Migration order

1. In `DocumentBuffer`, extract the verification/application core of `replaceMatches` into
   `applyMutation(expectedVersion, patches, undoPolicy)`; keep `replaceMatches` as a wrapper.
2. In `EditorEngine`, add `DocumentToken` (including the engine epoch), an atomic `InputSnapshot`,
   and `MutationResult`.
3. Add `EditorInputSession` next to `LazyTextEditor`; change `onTextReplace` from bare
   `TextPosition` values to session deltas.
4. Once typing uses that path, delete `isUserEditing`, `authorityEcho`, `contentsConverged` and
   `resyncSignal` from `LazyTextEditor`, and `latestReplaceRevision`, `resyncPending` and
   `_editResyncSignal` from `EditorWorkspaceViewModel`.
5. Convert cut, paste, delete and the oversized confirmation to prepared transactions. Remove public
   engine mutation APIs that accept only text or consult the current cursor/selection.
6. **Remove the global `EditCommand` queue last.** Until every caller uses verified requests, that
   queue is still containing real races — dismantling it first re-opens them.
7. Replace queue-order tests with protocol invariants: accepted transactions match their token and
   old text; conflicts leave content untouched; a field sequence converges to the same text as
   applying its local deltas; a foreign mutation invalidates all descendants of the old generation;
   async cut/confirm results can never mutate a different range.

## Alternatives considered and rejected

- **CRDT or operational transform.** Unnecessary for a single-user local editor; large complexity for
  a concurrency model that does not exist here.
- **A custom `InputConnection`.** Does not remove the replica problem, and adds a large surface of
  IME-compatibility risk.
- **Making the hidden field stateless** (reset to the engine echo after each event). Breaks IME
  composition for CJK, autocorrect and predictive text. Not viable on Android.
- **A full single-owner reducer** — one actor owning document, cursor, selection and undo, with slow
  effects executing outside and returning tagged results. Sound, and a reasonable end state, but a
  larger rewrite that *still* requires the input-session protocol, because the field must remain
  locally editable regardless.
