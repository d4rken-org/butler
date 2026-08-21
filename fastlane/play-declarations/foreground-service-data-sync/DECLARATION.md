# Declaration — Foreground service, `dataSync` (`FOREGROUND_SERVICE_DATA_SYNC`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → **Foreground service (FGS) permissions** declaration

This is a **separate form** from the Permissions declaration form that covers the other three
sensitive permissions in this folder. It is required for every declared foreground service type,
and an app that declares an FGS type without completing it fails review.

Declared in `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
...
<service
    android:name="eu.darken.butler.main.core.operations.fgs.OperationFgsService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

`OperationFgsService` is the only service Butler ever runs in the foreground. WorkManager's
`SystemForegroundService` is merged into the manifest with the same type by the WorkManager
library, but Butler has no worker that calls `setForeground`/`ForegroundInfo`, so it never starts.

---

## 1. Foreground service type to select

**Data sync** (`dataSync`).

---

## 2. "What is the core functionality this foreground service type is used for?" (paste into the form)

Butler is a file manager. Its foreground service exists for exactly one feature: **finishing a file
operation the user explicitly started** — copy, move, delete, and ZIP compress/extract — when the
user leaves the app while that operation is still running.

These operations move real user data between locations on the device: internal storage, SD cards,
USB drives, and archives. A single operation can be many gigabytes and run for minutes. If the
process is killed part-way, the user is left with a partially copied tree or a half-written archive,
which is data loss from the user's point of view.

The service is deliberately narrow:

- It is started **only** when an operation is already running *and* the user leaves the app
  (Home/Recents), in the foreground-eligible window Android allows. It is never started from the
  background, on boot, on a schedule, or on a push message.
- It is **stopped** as soon as the user returns to the app or the last operation finishes. While the
  app is on screen there is no foreground service and no notification — progress is shown in
  Butler's own in-app operations bar.
- While it runs, it posts an ongoing notification showing each operation's progress and a
  **Cancel** action, so the work is visible and stoppable at any moment.
- Android 15/16 cumulative `dataSync` timeouts are handled (`Service.onTimeout`): Butler stops the
  service gracefully rather than being force-stopped, and does not re-acquire it until operations
  drain.

No data is uploaded, downloaded, or synced to any server. Butler has no account, no cloud, and no
analytics; all of this happens locally on the device, which the privacy policy also states.

---

## 3. "Why can't you use an alternative API?" (paste into the form)

Google's suggested alternatives do not fit a local file transfer:

- **User-initiated data transfer job** (`JobInfo.Builder.setUserInitiated(true)`, API 34+) is the
  API Google names first for `dataSync`. It cannot express this work: a user-initiated data transfer
  job **requires a network constraint** (`setRequiredNetworkType`), because the API is designed for
  transfers to and from a network. Butler's operations are entirely local — storage volume to
  storage volume — and involve no network at all. It is also unavailable below API 34, while
  Butler's `minSdk` is 26.
- **WorkManager expedited work / `JobScheduler` deferrable jobs** are, by definition, deferrable and
  quota-limited. The operation is already in progress and holds open file handles; it cannot be
  deferred, rescheduled, or restarted from scratch without either losing the user's work or
  re-copying gigabytes of data.
- **Doing nothing** means the operation dies with the process. Android gives a backgrounded app no
  guarantee of continued execution, so a large copy started by the user would routinely fail
  half-way through with no way for the user to prevent it.

The foreground service is the only API that keeps an already-running, user-initiated, non-deferrable
local file transfer alive while showing the user what is happening and letting them cancel it.

---

## 4. Demo video

The form requires a video demonstrating the feature. Same hosting rules as the other declarations
(see [`../README.md`](../README.md#demo-video-required-for-all-files-access)): unlisted YouTube or
Google Drive with link sharing, under 2 minutes, 720p or better, English UI, real device.

**No automated recorder exists for this one yet** — the other three have a `record.sh`; this
storyboard is written to be recorded manually or automated later.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — Finishing a user-started file operation in the background (FOREGROUND_SERVICE_DATA_SYNC / dataSync)". |
| 2 | **User starts the work** | Explorer with a large folder selected → **Copy** → pick a destination on another volume → the operation starts, in-app operations bar shows live progress. No notification yet. |
| 3 | **User leaves the app** | Press Home while the copy is still running. |
| 4 | **The service appears** | Notification shade: Butler's ongoing notification with the operation name, live progress, and a visible **Cancel** action. This is the shot that justifies the type. |
| 5 | **Cancel is real** | Tap **Cancel** on a second operation to show the user is in control, then let the first one finish → the notification clears by itself. |
| 6 | **Return to the app** | Reopen Butler → no notification, progress back in the in-app bar → the operation completes and appears in the operation history. |
| 7 | **End card** | "The service runs only while a file operation you started is still finishing, and stops the moment it does. Nothing leaves your device." |

Shots 2–4 establish the required sequence: the user starts the work, the user leaves, and only then
does the foreground service exist. Shots 5–6 show it is bounded and cancellable.

---

## 5. Keep these consistent

- The **store listing** must keep documenting that file operations continue after the user leaves
  the app; Google cross-checks the declared feature against the listing.
  `fastlane/metadata/android/en-US/full_description.txt` covers it under *File operations*:
  "Operations keep running when you leave the app, with progress and a cancel button in the
  notification." Do not drop that line.
- The **privacy policy** states no data collection and on-device processing only; section 2's "no
  data is uploaded" claim depends on it staying true.
- Re-submit this form whenever the set of declared FGS types changes.

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/13392821>
