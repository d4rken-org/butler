# Butler SAF test provider

Standalone QA APK for testing Butler's Storage Access Framework resolution against
opaque document IDs and hostile child listings. It has one root in Android's
system document picker, named **Butler SAF test provider**, and no launcher UI.
Only the debug variant exists. It has no Butler module dependencies, flavors,
Hilt, Compose, or serialization dependencies.

## Build and install

Run from the repository root with the project's JDK 21.0.9 or newer:

```sh
./gradlew :saf-test-provider:assembleDebug
./gradlew :saf-test-provider:testDebugUnitTest
export ANDROID_SERIAL=YOUR_TEST_DEVICE_SERIAL
adb install -r saf-test-provider/build/outputs/apk/debug/saf-test-provider-debug.apk
```

The second command runs host-side Robolectric tests, without a device or emulator.
The APK uses application ID `eu.darken.butler.saftestprovider` and documents authority
`eu.darken.butler.saftestprovider.documents`. Its provider is exported, grants URI
permissions, and requires `android.permission.MANAGE_DOCUMENTS` for both reading
and writing. The system picker can grant Butler access to a tree normally.

## Scenarios

All IDs are decimal strings, independent of display names. Root ID is `1`.
Scenario folders themselves have valid names and stay fixed; their child files
and dynamically created directories support deletion and rename. Every directory
supports creation. Malformed rows only appear below selectable scenario folders.

| Folder key | ID | Seeded contents and purpose |
| --- | --- | --- |
| `plain` | `10` | `hello.txt` (`42`) and `other.txt` (`43`) have distinct contents. Happy path, writing, and readback. |
| `duplicates` | `20` | Two `dup.txt` rows (`44`, `45`) with different contents, plus unique `other.txt` (`46`). Resolving `dup.txt` must be inconclusive; `other.txt` remains usable. |
| `unnamed` | `30` | A null display-name row (`47`) and `visible.txt` (`48`). An unlisted name cannot establish absence. |
| `loading` | `50` | Initially only `partial.txt` (`51`) with `EXTRA_LOADING=true`. Completion exposes `complete.txt` (`52`) too and sets loading false. |
| `errored` | `60` | Navigable container for the two error variants below. |
| `errored/empty` | `61` | Zero rows with a string `EXTRA_ERROR`. |
| `errored/partial` | `62` | `partial.txt` (`63`) with a string `EXTRA_ERROR`. |
| `empty` | `70` | No rows or errors and loading false. Positive control: create succeeds, deleting a missing target returns false, strict existence reports `ABSENT`. |
| `deep` | `80` | `a` (`81`), `b` (`82`), `c` (`83`), `d` (`84`), then `file.txt` (`85`), each nested below the preceding directory. |
| `mutable` | `90` | `rename-me.txt` (`91`), plus space to create, delete, rename, and write/read back files and directories. Every successful rename returns a fresh ID. |

`root` is also a folder key. Nested deep keys are `deep/a`, `deep/a/b`,
`deep/a/b/c`, and `deep/a/b/c/d`. Controls also accept a current numeric directory
ID, including newly created directories.

The provider seeds tree metadata deterministically at process start. Controls,
created documents, renames, and deletions are process-local and revert on restart.
Existing seeded files retain their private backing bytes across ordinary process
restarts; missing seeded files are recreated. `reset` restores original bytes as
well as the exact tree, initial loading/error states, and allocation starting at
`1000`. It clears counters and the journal. Close active streams before resetting.
Do not carry dynamic document handles across resets or process restarts.

## Controls

Use the shell UID (normal `adb shell`, without `su`). The exported control provider
at `eu.darken.butler.saftestprovider.controls` has no manifest permission requirement
and accepts only shell UID 2000 or the app's own UID, checked with
`Binder.getCallingUid()`. It clears the calling identity after that check, acquires
the existing documents provider in the same process, and calls its control method
directly. The calling identity is restored and the provider client is closed even
when a control fails. The control authority supports only the methods below; other
methods and CRUD operations are rejected. Controls are idempotent: repeating them
leaves the requested tree/listing state unchanged;
`reset` starts a new journal session each time. `stats` does not record itself.

These are the direct `content call` commands for every method:

```sh
# Restore seeds and clear the journal.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method reset

# Return a partial loading listing, then publish its full contents.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method setLoading --arg loading
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method completeLoading --arg loading

# Set and clear an error string. --extra strings below avoid shell quoting issues.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method setError --arg errored/empty --extra message:s:Injected_empty_failure
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method setError --arg errored/partial --extra message:s:Injected_partial_failure
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method clearError --arg errored/empty
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method clearError --arg errored/partial

# Turn the previously unique c directory below b into two c rows.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method makeDuplicate --arg deep/a/b --extra name:s:c

# Without name, duplicate the first named child; repeat calls do not add a third.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method makeDuplicate --arg plain

# Counters and the first journal page.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method stats

# Subsequent page: replace 200 with the previous response's nextAfter value.
adb shell content call --uri content://eu.darken.butler.saftestprovider.controls --method stats --extra after:l:200 --extra limit:i:200
```

The folder may alternatively be supplied as `--extra folder:s:loading` instead of
`--arg loading`. `setError` requires a `message` string; `clearError` needs only the
folder. `makeDuplicate` requires an existing named child, creates a fresh ID with
the same name and MIME type, and does nothing if that name already has at least
two rows. A duplicate file has distinct contents; a duplicate directory is empty.

The content CLI cannot reach the documents provider. Device testing on an
Android 16 emulator verified both failures, before `ContentProvider.call` runs:

- Shell UID 2000 holds `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`, but acquisition is
  blocked by `MANAGE_DOCUMENTS`. `ContentProviderHelper.checkAssociationAndPermissionLocked`,
  reached through `ActivityManagerService.getContentProviderExternal`, throws
  `SecurityException`: "requires that you obtain access using ACTION_OPEN_DOCUMENT
  or related APIs". The fixture's own UID check is never reached.
- Running the content CLI through `run-as eu.darken.butler.saftestprovider` uses
  the provider's own UID and passes the documents-provider permission check, but
  `getContentProviderExternal()` throws `SecurityException`: "Permission Denial:
  Do not have permission in call getContentProviderExternal()" and "requires
  android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY".

The existing `call()` methods remain available to in-process and instrumentation
callers, with their UID checks and superclass dispatch intact, including Android's
standard document operations. The control authority is covered by host-side tests;
on-device verification of the new route remains a separate QA step.

## Journal and negative assertions

`stats` returns:

- `session`: fresh UUID on process start and every reset. Compare it between
  snapshots; a changed session invalidates a counter-delta assertion.
- One top-level long counter per method, also grouped in the `counters` Bundle:
  `queryChildDocuments`, `queryDocument`, `createDocument`, `deleteDocument`,
  `renameDocument`, `openDocument`, and `isChildDocument`.
- `journal`: newline-separated records, with tab-separated fields in this order:
  `sequence`, `method`, `documentId`, `timestamp`, `parentDocumentId`.
  Timestamp is `SystemClock.elapsedRealtime()` in milliseconds. Sequence starts
  at 1 and resolves ties. The last field is populated for `isChildDocument`.
  Creation and child-listing entries use the parent ID as `documentId`.
- `totalEntries`, `nextAfter`, and `hasMore`: pagination metadata. `after` is an
  exclusive sequence offset (default 0); `limit` defaults to 200 and is clamped
  to 1 through 1000. All entries stay available for the current process/session.
  Continue with `after=nextAfter` while `hasMore=true`.

Counters and each page are captured together under the provider's state lock.
Methods are recorded on entry, including failed attempts. A framework permission
rejection before entry is not a provider-method call. Control methods and
`queryRoots` are excluded. Stats reads do not alter counters or append entries.
The journal is in memory; reset between test cases to bound its size.

For a negative mutation assertion:

1. Reset, establish the required tree grant in Butler, prepare the scenario,
   and capture `stats` as the baseline. Picker navigation also makes provider
   calls, so capture the baseline after navigation.
2. Perform exactly one Butler operation, such as creating a requested name under
   an errored or loading folder. Let the operation finish and capture `stats` again.
3. Require the same `session`, and a zero delta in `createDocument`,
   `deleteDocument`, or `renameDocument`, whichever the operation must avoid.
   Inspect entries after the baseline `totalEntries` to establish that the
   intended lookup actually occurred. A UI error alone proves nothing about
   whether a mutation was attempted.
4. Repeat against `empty`: a missing name must be proven absent, permitting
   creation. A missing-target deletion must return false without a provider
   `deleteDocument` call. Strict existence must report `ABSENT`.

Butler resolution should return a handle for a resolved name, `null` only for
proven absence, and `ReadException` for an inconclusive listing. Strict existence
translates inconclusive to `UNKNOWN`; mutations wrap it in `WriteException`.
Avoid unrelated browsing while collecting a journal interval.

## Loading notifications and Butler caches

Every children cursor calls `setNotificationUri` with the canonical URI:

```text
content://eu.darken.butler.saftestprovider.documents/document/50/children
```

Here `50` is the loading parent ID; other folders use their own IDs. It is built
with `DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)`,
without a tree URI prefix. `completeLoading` removes the loading flag while
holding the state lock, making the full child set visible, then calls
`notifyChange` on that same URI. Queries never notify or complete loading.
Register the observer after receiving the partial cursor, then invoke completion
from the shell. There are no completion timers or polled control files.

Notification alone does **not** invalidate Butler's listing caches, which last
approximately 10 seconds. QA must force an **explicit refresh** in Butler after
changing fixture state instead of expecting its UI to update automatically.
A waiting observer and a fresh query can test provider notification behavior
separately from Butler's cache behavior.

## Cold deep-tree and stale-directory checks

In Butler's system tree picker, grant **at `deep/a/b`** (ID `82`), then avoid
browsing into `c` or `d`. The tree URI is:

```text
content://eu.darken.butler.saftestprovider.documents/tree/82
```

Restart Butler after retaining the grant to clear warmed resolution caches.
Access the relative path `c/d/file.txt` directly through the SAF caller being
tested, without listing its intermediate directories first. The bottom handle is:

```text
content://eu.darken.butler.saftestprovider.documents/tree/82/document/85
```

The root advertises `FLAG_SUPPORTS_IS_CHILD`; `isChildDocument` walks actual
parent links for arbitrary depth, independently of any query cache.

For a unique directory becoming ambiguous, first access `c/d/file.txt`, then run
`makeDuplicate --arg deep/a/b --extra name:s:c` as shown above. Repeat the operation
immediately to exercise the existing cached handle, then explicitly refresh and
repeat to exercise fresh resolution. Record stats for each interval. The two `c`
rows must make name resolution inconclusive; the original ID `83` and its actual
descendants still exist. Reset before repeating the scenario.
