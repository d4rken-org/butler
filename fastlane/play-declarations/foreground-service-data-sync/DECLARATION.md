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

## Play Console form

The live form has no free-text field. It opens with *"Your app uses the FOREGROUND_SERVICE_DATA_SYNC
permission. You can only use this permission if your app performs tasks noticeable to the user when
they're not directly interacting with your app."*, then asks for a task selection and a video link.

### Task selection

*"What tasks require your app to use the FOREGROUND_SERVICE_DATA_SYNC permission?"*, with
checkboxes in three groups.

| Group | Task | Tick |
|-------|------|------|
| Network processing | Backing up, restoring | no |
| Network processing | Other | **untick it** |
| Local processing | Media transcoding | no |
| Local processing | Importing, exporting | **yes** |
| Local processing | Other | **yes** |
| Other tasks | Other | no |

**Network processing > Other arrives pre-ticked and must be unticked.** Butler's operations do no
network I/O at all, so leaving it ticked would make the declaration untrue.

**Local processing > Other** and **Local processing > Importing, exporting** are the two to tick.
What they cover: finishing an operation the user started, after the user has left the app while it
is still running. That is a copy, a move, a delete, an archive being written or extracted between
filesystem locations the user picked, and the save of a file another app handed to Butler.

Why each of the three Local processing boxes is ticked or not:

- *Other* is ticked: the bulk of the work is Butler moving the user's existing files between
  locations the user chose, which none of the named tasks describes.
- *Importing, exporting* is ticked: another app can share a file to Butler, and Butler's save-as
  then writes it wherever the user points it. That save is submitted to the same operations
  manager and is protected by the same foreground service, and it is data being brought into the
  app rather than moved between two locations the user already had.
- *Media transcoding* stays unticked: Butler does not transcode media. It copies bytes, it does not
  re-encode them.
- *Backing up, restoring* stays unticked, and it sits in the Network processing group anyway, where
  Butler transfers nothing to or from a network.

### Video

Ticking a task reveals *"Provide a video demonstrating how your app uses the
FOREGROUND_SERVICE_DATA_SYNC permission for the tasks you've selected"* and a **Video link** field.

**No video exists yet.** Unlike the other three declarations, this one has no `record.sh`, and
nothing has been recorded manually either. The demand is per ticked task, so the video has to show
**both**: the copy scenario for *Local processing > Other*, and the Save-as scenario for *Local
processing > Importing, exporting*. Footage of only one of them leaves a ticked task
undemonstrated, which invites a rejection. **This form cannot be submitted** until both scenarios
in the storyboard in [§4](#4-demo-video) have been recorded and hosted per
[`../README.md`](../README.md#demo-videos).

---

## If the form asks for a description

Google's FGS help page says a per-type description is required, but the form as captured shows no
such field. These two blocks are staged for that case, and for a policy email or appeal reply.
They are not console fields today. Each is within the 500-character cap the other declaration
forms use.

Core functionality:

```text
Butler is a file manager. The service exists for one feature: finishing an operation the user already started, when the user leaves the app while it is still running. That covers copying, moving, deleting, archiving and extracting the user's files, and saving a file another app handed to Butler. These run for minutes; killing the process leaves a half-copied folder or a truncated archive. It posts an ongoing notification with progress and a Cancel action, and stops once the user returns.
```

Why an alternative API does not work:

```text
A user-initiated data transfer job requires a network constraint, because that API exists for transfers to and from a network. Butler's operations move the user's files between storage locations, or write a file another app handed over, and make no network request, so the job cannot express them. It is also API 34 and later, while Butler's minSdk is 26. Deferrable work (WorkManager, JobScheduler) does not fit either: the operation is already running with open file handles.
```

---

## 1. Foreground service type to select

**Data sync** (`dataSync`).

---

## 2. "What is the core functionality this foreground service type is used for?" (reference, not a form field)

Butler is a file manager. Its foreground service exists for exactly one feature: **finishing a file
operation the user explicitly started** — copy, move, delete, compress into an archive (ZIP, TAR,
TAR.GZ or TAR.BZ2), extract one, and save a file another app handed to Butler — when the user
leaves the app while that operation is still running.

These operations move real user data between locations on the device: internal storage, SD cards,
USB drives, and archives. The save case is the same machinery: another app shares a file to Butler,
and Butler writes it where the user points it. A single operation can be many gigabytes and run for
minutes. If the process is killed part-way, the user is left with a partially copied tree or a
half-written archive, which is data loss from the user's point of view.

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

These file operations involve no network. Butler has no account, no cloud, and no analytics; the
work happens locally on the device, which the privacy policy also states.

---

## 3. "Why can't you use an alternative API?" (reference, not a form field)

Google's suggested alternatives do not fit a local file transfer:

- **User-initiated data transfer job** (`JobInfo.Builder.setUserInitiated(true)`, API 34+) is the
  API Google names first for `dataSync`. It cannot express this work: a user-initiated data transfer
  job **requires a network constraint** (`setRequiredNetworkType`), because the API is designed for
  transfers to and from a network. Butler's operations run from one storage location to another, or
  write out a file another app handed over, and involve no network at all. Availability is the secondary point: the API starts at 34, while
  Butler's `minSdk` is 26, so it could at best cover newer devices.
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
(see [`../README.md`](../README.md#demo-videos)): unlisted YouTube or Google Drive with link
sharing, under 2 minutes, 720p or better, English UI, real device.

**No automated recorder exists for this one yet** — the other three have a `record.sh`; this
storyboard is written to be recorded manually or automated later.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — Finishing a user-started file operation in the background (FOREGROUND_SERVICE_DATA_SYNC / dataSync)". |
| 2 | **User starts the work** | Explorer with a large folder selected → **Copy** → pick a destination on another volume → the operation starts, in-app operations bar shows live progress. Start a second, smaller copy as well, so two operations are running. No notification yet. |
| 3 | **User leaves the app** | Press Home while both copies are still running. |
| 4 | **The service appears** | Notification shade: Butler's ongoing notification with the operation names, live progress, and a visible **Cancel** action. This is the shot that justifies the type. |
| 5 | **Cancel is real** | Tap **Cancel** on the second operation. It stops and drops out of the notification, while the first copy keeps running with live progress. The user is in control of each operation. |
| 6 | **Return to the app** | Reopen Butler: the foreground service stops because the app is on screen again and the notification disappears. Progress is back in the in-app operations bar, where the first copy runs to completion and then appears in the operation history. |
| 7 | **A file arrives from another app** | Android's own Files app: long-press a large file, **Share**, pick **Butler**. Butler's arrival dialog offers **Save as**; pick a destination and tap **Save**. The save starts and runs with live progress in the in-app operations bar. |
| 8 | **User leaves during the save** | Press Home while the save is still running. |
| 9 | **The service covers the import too** | Notification shade: Butler's ongoing notification with the save's live progress and a **Cancel** action. |
| 10 | **Return to the app** | Reopen Butler: the notification disappears and the save continues, then completes, in the in-app operations bar. |
| 11 | **End card** | "The service runs only while a file operation you started is still finishing, and stops the moment it does. Nothing leaves your device." |

Shots 2–4 establish the required sequence: the user starts the work, the user leaves, and only then
does the foreground service exist. Shot 5 shows the work is cancellable per operation, and shot 6
shows it is bounded: the service is gone as soon as the app is back on screen, and the surviving
copy finishes in the in-app bar.

Shots 7–10 are the second required scenario, not an optional extra. The form asks for a video
demonstrating the permission **for the tasks you've selected**, and *Local processing > Importing,
exporting* is ticked, so the import case has to be on camera as well: another app shares a file to
Butler, Butler's save writes it to the location the user picks, and that operation goes through the
same operations manager and the same foreground service as a copy. Pick a file large enough that
the save is still running when Home is pressed, otherwise the notification never appears. A ticked
task with no footage behind it invites a rejection.

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the FOREGROUND_SERVICE_DATA_SYNC permission. The recording is still to be made; this description matches its storyboard.

1. In Butler's file explorer the user copies a large folder to another storage volume, then starts a second, smaller copy. Both run with live progress in the in-app operations bar. There is no notification while the app is on screen.
2. The user presses Home while both copies are still running.
3. The notification shade shows Butler's ongoing notification: the running operations, their live progress and a Cancel action. The foreground service exists only in this situation, keeping an operation the user already started from being killed part-way.
4. Cancel is tapped on the second operation. It stops and leaves the notification while the first copy carries on.
5. The user reopens Butler. The service stops because the app is on screen again and the notification disappears; the first copy finishes in the in-app operations bar and is listed in the operation history.
6. The second scenario is an import. In Android's Files app the user shares a large file to Butler, Butler's arrival dialog offers Save as, and the user picks a destination and taps Save. The save runs with live progress in the operations bar.
7. The user presses Home while that save is still running.
8. Butler's ongoing notification shows the save's live progress and a Cancel action: the same service, now keeping a file another app handed over from being written only half way.
9. The user reopens Butler. The notification disappears and the save finishes in the operations bar.

The same service covers Butler's other operations: moving, deleting, compressing into an archive and extracting one. It all runs on the device, involves no network, and nothing is collected or used for analytics or advertising; details leave the device only inside a debug log or crash report the user chooses to share.
```

---

## 5. Keep these consistent

- The **store listing** must keep documenting that file operations continue after the user leaves
  the app; Google cross-checks the declared feature against the listing.
  `fastlane/metadata/android/en-US/full_description.txt` covers it under *File operations*:
  "Operations keep running when you leave the app, with progress and a cancel button in the
  notification." Do not drop that line.
- The **privacy policy** states on-device processing only; section 2's "no network" claim depends
  on it staying true.
- Re-submit this form whenever the set of declared FGS types changes.

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/13392821>
