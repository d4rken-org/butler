# Play Console — Permissions Declarations

Copy-paste source for the **Permissions Declaration Form** in Google Play Console
(App content → *Sensitive app permissions* / *Permissions declaration*). Butler declares two
sensitive permissions that gate review — they must be declared **and approved** before the app
can pass review, including for closed testing.

One directory per permission rationale, each holding its declaration text and a
recorder script for its demo video:

| Folder | Permission | Gates | Google use case to select |
|--------|------------|-------|---------------------------|
| [`manage-external-storage/`](./manage-external-storage/DECLARATION.md) | `android.permission.MANAGE_EXTERNAL_STORAGE` | Core file-manager features | **File management** |
| [`query-all-packages/`](./query-all-packages/DECLARATION.md) | `android.permission.QUERY_ALL_PACKAGES` | App Manager | **File managers** |

Package name: `eu.darken.butler`

## How to use

1. Open Play Console → your app → **App content** → **Permissions declaration form**.
2. For each permission, select the use case named in the table above.
3. Paste the **“How your app uses this permission”** text from the matching file.
4. Paste the **“Why an alternative API is not sufficient”** text (All files access requires this).
5. For All files access, add the **demo video link** (see below).
6. Do **not** submit until a build that contains the permission has been uploaded to a track.

## Demo video (required for All files access)

The form requires a short video demonstrating the core functionality that needs the permission.

- Length: **under 2 minutes** (60–90s is plenty). Resolution **720p or higher**.
- Record on a **real device** with the **English** UI.
- Must visibly show: the permission being granted, **and** each core feature using it.
- Host as an **unlisted YouTube** video or a **Google Drive** file with link-sharing enabled
  (“anyone with the link can view”), then paste that URL into the form.
- A storyboard is in [`manage-external-storage/DECLARATION.md`](./manage-external-storage/DECLARATION.md#4-demo-video-script),
  and both demos can be regenerated automatically (see "Generating the demo videos" below).

## Keep these consistent

- **Store listing**: the file-management and app-manager features must stay **prominently
  documented** in `fastlane/metadata/android/en-US/full_description.txt` — Google cross-checks.
- **Privacy policy**: `PRIVACY_POLICY.md` states no data is collected and all processing is
  on-device. The declarations rely on this — do not contradict it.
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

# NOREC=1 ./<permission>/record.sh ...   # validate the tap chain without recording
```

Each output is portrait 720×1606 H.264, well under the 2-minute limit, and uses
only synthetic/seeded data — nothing personal is uploaded.

- **`manage-external-storage`** (~104 s): feature blocked → grant → browse a deep
  **non-media** folder (logs/configs/docs) → bulk-move them across directories →
  **whole-volume recursive search** (`report` → hits in `Documents/Work` and
  `Download`) → Tab Manager showing live previews of both the Explorer and Searcher
  tabs. The three shots map to the three reasons SAF/MediaStore are insufficient:
  non-media files, bulk cross-tree moves, and per-folder-grant-free search.
- **`query-all-packages`** (~50 s): App Manager breadth (user + system app counts)
  → **search across all installed apps** → deep app detail → **Export APK into the
  file explorer** (Export → Save-as → the `.apk` lands in `Download`, browsable in
  Butler). Breadth + search show why broad package visibility is required; the export
  shot ties the permission to the "File managers" permitted use.

## Policy references

- All files access: <https://support.google.com/googleplay/android-developer/answer/10467955>
- Query all packages: <https://support.google.com/googleplay/android-developer/answer/10158779>
