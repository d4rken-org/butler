# Play Console — Permissions Declarations

Copy-paste source for the review-gating **App content** forms in Google Play Console. Butler
declares three sensitive permissions plus one foreground service type — all of them must be
declared **and approved** before the app can pass review, including for closed testing.

The three permissions go through the **Permissions Declaration Form**
(App content → *Sensitive app permissions* / *Permissions declaration*). The foreground service
type goes through a **separate** form; see [Foreground service](#foreground-service) below.

One directory per permission rationale, each holding its declaration text and a
recorder script for its demo video:

| Folder | Permission | Gates | Google use case to select |
|--------|------------|-------|---------------------------|
| [`manage-external-storage/`](./manage-external-storage/DECLARATION.md) | `android.permission.MANAGE_EXTERNAL_STORAGE` | Core file-manager features | **File management** |
| [`query-all-packages/`](./query-all-packages/DECLARATION.md) | `android.permission.QUERY_ALL_PACKAGES` | App Manager | **File managers** |
| [`request-install-packages/`](./request-install-packages/DECLARATION.md) | `android.permission.REQUEST_INSTALL_PACKAGES` | Opening APK files ("Open with") | **File sharing, transfer or management** |

Package name: `eu.darken.butler`

## Foreground service

Filed separately, under App content → **Foreground service (FGS) permissions**. Declaring an FGS
type without completing this form fails review.

| Folder | Type | Gates | Google FGS type to select |
|--------|------|-------|---------------------------|
| [`foreground-service-data-sync/`](./foreground-service-data-sync/DECLARATION.md) | `dataSync` (`FOREGROUND_SERVICE_DATA_SYNC`) | Finishing a user-started copy, move, delete, archive or save after leaving the app | **Data sync** |

It needs its own demo video. Unlike the three permissions above, it has **no `record.sh` yet** —
the storyboard in its `DECLARATION.md` is written to be recorded manually.

## Field map

The four forms do **not** share one shape. Two of them have no free-text field at all, and the
number of text boxes differs between the other two, so there is no single "paste the rationale"
step that works everywhere. Fill each one from the map below.

| Form | Fields it actually has | Paste source |
|------|------------------------|--------------|
| All files access | Two free-text boxes: *All files access* (describe 1 feature) and *Technical reason* (why not SAF or MediaStore). One `Usage` checkbox group. A demo video. | [`manage-external-storage/DECLARATION.md`](./manage-external-storage/DECLARATION.md#play-console-form), section *Play Console form* |
| Query all packages | **One** free-text box: *Core purpose* (describe 1 feature). One `Usage` checkbox group. A video link field. There is no alternative-API box, so the single block carries both the feature and the reason `<queries>` is not enough. | [`query-all-packages/DECLARATION.md`](./query-all-packages/DECLARATION.md#play-console-form), section *Play Console form* |
| Request install packages | **No free text.** A *Core purpose* checkbox group (permitted functionality) plus the `Usage` checkbox group, and a video link field. The video is the only evidence Google gets. | [`request-install-packages/DECLARATION.md`](./request-install-packages/DECLARATION.md#play-console-form), section *Play Console form* |
| Foreground service, `dataSync` | **No free text.** A task checkbox group in three sections (Network processing, Local processing, Other tasks), and a video link that appears once a task is ticked. | [`foreground-service-data-sync/DECLARATION.md`](./foreground-service-data-sync/DECLARATION.md#play-console-form), section *Play Console form* |

Rules that apply to all of them:

- Every free-text box is capped at **500 characters**. The blocks in the declarations are written
  to sit just under that cap.
- The `## Play Console form` section of a declaration is the **only** paste source. The numbered
  sections below it are background for whoever fills the form in and for a policy follow-up; they
  are longer than 500 characters and are labelled *(reference, not a form field)*.
- Paste-ready text always sits in a fenced block opened with ```` ```text ````, and nothing else in
  these files uses that fence. Checks that extract and character-count the blocks key on it, so
  keep it exact.
- The checkbox groups are recorded as checked/unchecked lists with a one-line reason per box. The
  reasons for the *unticked* boxes matter as much as the ticked ones, especially Analytics and
  Advertising, which are the uses the policies name as invalid.
- Do **not** submit until a build that contains the permission has been uploaded to a track.

## Submission readiness

- **All files access** and **Query all packages**: ready, videos recorded.
- **Request install packages**: the video must be **re-recorded** first. The recorder now films the
  user receiving an APK as a file (copy out of `Download/incoming` into `Download`) before the
  install flow, because the permitted use requires both halves and the form has no text field to
  argue the point in. Any previously recorded video is missing that beat.
- **Foreground service, `dataSync`**: **not submittable yet.** No video has been recorded and there
  is no recorder for it.

## Demo videos

All four forms take a video. All files access requires one; the other three have a video link field
and the two with no free-text field depend on it entirely.

- Length: **under 2 minutes**; the QUERY_ALL_PACKAGES form recommends 90 seconds or shorter.
  Resolution **720p or higher**.
- Record on a **real device** with the **English** UI.
- Must visibly show: the permission being granted (where there is a grant), **and** each core
  feature using it.
- Host as an **unlisted YouTube** video or a **Google Drive** file with link-sharing enabled
  ("anyone with the link can view"), then paste that URL into the form.
- Each declaration's `## 4.` section holds its storyboard **and** a plain-text
  *Video description (paste into YouTube)* block to publish alongside the video.
- Three of the four can be regenerated automatically (see "Generating the demo videos" below); the
  foreground-service one has to be recorded by hand.

## Keep these consistent

- **Store listing**: the file-management and app-manager features must stay **prominently
  documented** in `fastlane/metadata/android/en-US/full_description.txt` — Google cross-checks.
- **Privacy policy**: `PRIVACY_POLICY.md` states that processing is on-device, that nothing is
  collected for analytics or advertising, and that details leave the device only inside a debug log
  or crash report the user shares. The declarations are written to match that exactly, including
  the qualification. Do not restore any unqualified "never leaves the device" claim in either
  place.
- **Resubmit** the form whenever permission usage changes.

## Generating the demo videos (automated)

The videos are regenerated on an emulator with no manual screen-recording. Each
recorder drives Butler with label-based taps (robust to layout changes) while
`screenrecord` captures, then post-processing adds a title card, burned-in step
captions, and an end card. **The scripts are committed; the `*.mp4` outputs are
git-ignored** (see `.gitignore`).

Shared (this folder):

| File | Role |
|------|------|
| `_common.sh` | UI helpers (label-based tap/scroll), clean-tabs-to-picker, screenrecord start/stop |
| `find_node.py` | Locates a UI node by text/content-desc in a `uiautomator` dump |
| `seed_demo_data.sh [serial]` | Lays a believable synthetic file tree on `/sdcard` |
| `postprocess.sh [OUTDIR]` | ffmpeg: title + captions + end card → `<OUTDIR>/declaration.mp4` |

Per permission: `<permission>/record.sh` (pre-state + the recorded flow + its
title/end-card text) and `<permission>/DECLARATION.md` (the Play Console copy).

```bash
# emulator must have Butler installed; default serial emulator-5564 (butler-main-2)

# All-files-access demo
./manage-external-storage/record.sh emulator-5564
./postprocess.sh /tmp/butler-demo/manage-external-storage

# App-manager (query-all-packages) demo
./query-all-packages/record.sh emulator-5564
./postprocess.sh /tmp/butler-demo/query-all-packages

# APK-install (request-install-packages) demo — the install-source grant must be
# PRISTINE so the source-approval shot is captured. The recorder ABORTS if it is
# not; reset it first by reinstalling Butler (cheap) or wiping the emulator:
#   adb -s emulator-5578 uninstall eu.darken.butler
#   adb -s emulator-5578 install -g app/build/outputs/apk/foss/debug/app-foss-debug.apk
./request-install-packages/record.sh emulator-5578
./postprocess.sh /tmp/butler-demo/request-install-packages

# NOREC=1 ./<permission>/record.sh ...   # validate the tap chain without recording
```

Each output is portrait 720×1606 H.264, well under the 2-minute limit, and uses
only synthetic/seeded data — nothing personal is uploaded.

- **`manage-external-storage`** (~104 s): feature blocked → grant → browse a deep
  **non-media** folder (logs/configs/docs) → bulk-move them across directories →
  **whole-volume recursive search** (`report` → hits in `Documents/Work` and
  `Download`) → Tab Manager showing live previews of both the Explorer and Searcher
  tabs. The three shots map to the three reasons SAF/MediaStore are insufficient:
  non-media files, bulk cross-tree moves, and traversing a whole volume from a
  single permission instead of picking every tree in the SAF picker.
- **`request-install-packages`** (~88 s): an `.apk` arrives in `Download/incoming`
  and the user **copies it into `Download`** (the "receiving app packages" half) →
  **"Open with"** hands it to the system installer → Android's per-source
  **"Install unknown apps"** grant for Butler (the policy-critical shot) → the user
  confirms **Install** in the system installer → "App installed". Shows both halves
  of the permitted use and all three user decisions that gate an install; the
  installed app is CapOd (same developer, pinned URL + SHA-256).
- **`query-all-packages`** (~50 s): App Manager breadth (user + system app counts)
  → **search across all installed apps** → deep app detail → **Export APK into the
  file explorer** (Export → Save-as → the `.apk` lands in `Download`, browsable in
  Butler). Breadth + search show why broad package visibility is required; the export
  shot ties the permission to the "File managers" permitted use.

## Policy references

- All files access: <https://support.google.com/googleplay/android-developer/answer/10467955>
- Query all packages: <https://support.google.com/googleplay/android-developer/answer/10158779>
- Request install packages: <https://support.google.com/googleplay/android-developer/answer/12085295>
- Foreground service types: <https://support.google.com/googleplay/android-developer/answer/13392821>
