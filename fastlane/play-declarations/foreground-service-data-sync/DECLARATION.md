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
| Local processing | Importing, exporting | no |
| Local processing | Other | **yes** |
| Other tasks | Other | no |

**Network processing > Other arrives pre-ticked and must be unticked.** Butler's operations do no
network I/O at all, so leaving it ticked would make the declaration untrue.

**Local processing > Other** is the only box to tick. What it covers: finishing an operation the
user started, after the user has left the app while it is still running. That is a copy, a move, a
delete, an archive being written or extracted between filesystem locations the user picked, and the
save of a file another app handed to Butler.

Why each of the three Local processing boxes is ticked or not:

- *Other* is ticked: all of the work is Butler finishing an operation on the user's files, and none
  of the named tasks describes it.
- *Importing, exporting* stays unticked, and this is a deliberate choice rather than an oversight.
  The save of a file another app shares into Butler does go through the same operations manager and
  the same foreground service, so an argument exists for ticking it. But the form gives no
  definition of the category, *Other* already covers that save, and the form asks for a video
  demonstrating the permission "for the tasks you've selected". Ticking a second task obliges a
  second recorded scenario; ticking one that adds no coverage would mean declaring a task the video
  does not show, which is a rejection risk taken for nothing.
- *Media transcoding* stays unticked: Butler does not transcode media. It copies bytes, it does not
  re-encode them.
- *Backing up, restoring* stays unticked, and it sits in the Network processing group anyway, where
  Butler transfers nothing to or from a network.

### Video

Ticking a task reveals *"Provide a video demonstrating how your app uses the
FOREGROUND_SERVICE_DATA_SYNC permission for the tasks you've selected"* and a **Video link** field.

The demand is per ticked task, and only *Local processing > Other* is ticked, so one scenario has
to be on camera: a copy the user started, still running after they leave the app. That is what
`./record.sh` produces. Host it per [`../README.md`](../README.md#demo-videos) and paste the link.

**Known gap in the current recording.** The notification renders collapsed, inside the shade's
"Silent" section, so the row shows the operation and its progress bar but **not** its Cancel
action. User control is the heart of the FGS criteria, so that shot is worth having: re-record with
the Butler notification expanded before submitting, or accept that the written description carries
the cancellation claim alone. The recorder is otherwise complete and re-runnable.

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

`./record.sh <adb-serial>` records it, then `../postprocess.sh` adds the cards and captions.
**Record this one on a real device.** On an emulator the storage is host-backed and far too fast
for the shot to exist: a 3 GB copy finishes in about a second, so the operation is always over
before the user can leave the app. The same copy on a Pixel 3a takes roughly 48 seconds, which is
what makes the sequence filmable at all. `SCENARIO_B=0` is the default path today; see the note
below the table.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — Finishing your file operations after you leave the app (FOREGROUND_SERVICE_DATA_SYNC)". |
| 2 | **User starts the work** | Explorer on the demo tree: long-press a large folder, **Copy**, open the destination folder, **Paste**. The copy starts and runs in the in-app operations bar. No notification while the app is on screen, which is the point. |
| 3 | **User leaves the app** | Press Home while the copy is still running. |
| 4 | **The service appears** | Notification shade: Butler's ongoing notification naming the operation, with live progress and a **Cancel** action. This is the shot that justifies the type. |
| 5 | **Return to the app** | Reopen Butler: the foreground service stops because the app is on screen again and the notification disappears, while the copy carries on in the in-app operations bar. The service is bounded by the user's absence. |
| 6 | **End card** | "The service runs only while an operation you started is still finishing. These file operations run entirely on your device." |

Shots 2–4 establish the required sequence: the user starts the work, the user leaves, and only then
does the foreground service exist. Shot 5 shows the other half, that it is bounded: the moment the
app is back on screen the service is gone and the work continues in-app.

**The Cancel action is not in frame in the current recording.** The notification renders collapsed,
in the shade's "Silent" section, so shot 4 shows the operation name and its progress bar but not
its Cancel button. Since user control is central to the FGS criteria, that shot is worth
re-recording with the notification expanded.

**On the second scenario.** The service also covers a Save-as import, a file another app shares
into Butler. `record.sh` can film that too (`SCENARIO_B=1`), but it drives the system Files app,
whose package differs per build: AOSP ships `com.android.documentsui`, a Pixel ships
`com.google.android.documentsui`, and "Files by Google" is a third thing again. It is off by
default and the form does not need it, because only *Local processing > Other* is ticked and that
covers the import as well.

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the FOREGROUND_SERVICE_DATA_SYNC permission, recorded on a Pixel 3a.

1. In Butler's file explorer the user copies a large folder to another folder. The copy runs with live progress in the in-app operations bar. There is no notification while the app is on screen.
2. The user presses Home while the copy is still running.
3. The notification shade shows Butler's ongoing notification: the operation, its live progress and a Cancel action. The foreground service exists only in this situation, keeping an operation the user already started from being killed part-way through and leaving a half-copied folder behind.
4. The user reopens Butler. The service stops because the app is on screen again and the notification disappears, while the copy carries on in the in-app operations bar.

The same service covers Butler's other file operations: moving, deleting, compressing into an archive and extracting one, and saving a file another app has shared into Butler. It all runs on the device, involves no network, and nothing is collected or used for analytics or advertising; details leave the device only inside a debug log or crash report the user chooses to share.
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
