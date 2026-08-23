# Declaration — All files access (`MANAGE_EXTERNAL_STORAGE`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **All files access**

## Play Console form

The live form asks for two free-text boxes, a checkbox group, and a video, in the order below.
Both text boxes are capped at **500 characters**, and the fenced blocks here are the paste source.
Sections 1 to 4 are background for whoever fills the form in, not fields.

### All files access (500 characters)

Prompt: *"Describe 1 feature in your app that requires a permitted use of the All files access
permission."*, with the helper line *"Approval will be granted for your entire app, not just for
this feature."* One feature, described as one feature, not a bulleted list of everything the app
does.

```text
Butler is a file manager. The feature is browsing the device's shared storage and managing what is there: opening any folder the user navigates to, then copying, moving, renaming, compressing, sharing and deleting files of any type, with bulk multi-select and a trash that restores an item to the exact path it came from. It must work on every path the user can reach, not only on media. Processing is on-device; a file leaves Butler only when the user shares it or a diagnostic report.
```

### Usage

*"Why does your app need to use the All files access permission? Select all that apply."*

- [x] **Core functionality**: browsing and managing files is what the app is for.
- [ ] Personalization: the permission tailors nothing to the user.
- [ ] Security or fraud prevention: Butler does no scanning, no threat detection.
- [ ] Analytics: Butler has no analytics.
- [ ] Ads or monetization: All-files access is not used to serve ads or to monetize the app.

### Technical reason (500 characters)

Prompt: *"Explain why your app can't make use of more privacy friendly best practices, such as the
Storage Access Framework, or the Media Store API. Improving the performance of your app is not an
acceptable reason"*. Speed is therefore never used as an argument, here or in §3.

```text
The Storage Access Framework makes the user pick each tree in the system picker, and Android 11 and later refuses a grant on the storage root, on Download, and on Android/data and Android/obb, so a file manager cannot reach everything it must. Every extra volume needs its own pick. moveDocument() is optional per document provider, so a cross-tree move can fail, and symbolic links cannot be represented at all. MediaStore gives no read or write access to arbitrary non-media files of other apps.
```

### Demo video

A demo video is required for this form. The storyboard and the description to paste with it are in
[§4](#4-demo-video-script); the hosting rules are in [`../README.md`](../README.md#demo-videos).
The video field sits below the part of the form that the screenshots this section was written from
captured, so its exact label is not recorded here.

---

## 1. Permitted use to select

**File management.**

Google's permitted-use wording (file manager), which Butler matches exactly:

> "App's core purpose involves the access, editing, and management (including maintenance) of
> files and folders outside of its app-specific storage space."

---

## 2. "How does your app use this permission?" (reference, not a form field)

Butler is a general-purpose file manager. Its core, primary purpose is to let the user access,
edit, and manage — including maintenance — files and folders across the device's shared storage,
outside the app's own app-specific directory.

Features that depend on broad file access, all of which are the app's main, prominently
advertised functionality:

- Browsing the entire shared-storage tree and operating on files of **any type**: not only media,
  but non-media files such as logs, configuration files, source code, archives, and databases.
- Copying, moving, and deleting **files and whole folders** across arbitrary, user-chosen
  directories, including bulk (multi-select) operations and background operations with progress
  reporting.
- A trash/recycle bin: moving items to trash and restoring them to their original location
  (file maintenance).
- Recursive search — for files by name and for text **within** files — across an entire
  user-selected directory tree.
- A text editor that opens and saves files in place at arbitrary paths (configs, logs, large
  files).

All file access is performed **only on the device** to provide these features. File contents and
file listings are not collected and are not used for analytics or advertising; a file leaves Butler
only when the user shares it to another app, or inside a debug log or crash report the user chooses
to share. (See the app's privacy policy.)

This is **not** manual, single-file selection — it is continuous management and maintenance of
whole directory trees the user navigates, which the Storage Access Framework file picker cannot
provide (see below).

---

## 3. "Why is an alternative API not sufficient?" (reference, not a form field)

Butler cannot deliver its core file-management functionality with the Storage Access Framework
(SAF) or the MediaStore API:

- **MediaStore does not manage arbitrary non-media files.** `MediaStore.Files` and
  `MediaStore.Downloads` exist, but they give an app no read/write access to arbitrary non-media
  files owned by other apps, and non-media files outside the app's own entries are not manageable
  through it. A file manager has to handle logs, configuration files, source code, archives and
  databases wherever they happen to sit.
- **SAF hands out user-picked roots, not the device.** The user has to select each tree in the
  system picker, and on Android 11 and later the picker refuses a grant on the storage root
  itself, on `Download`, and on `Android/data` and `Android/obb`. The single grant that would
  cover the whole volume is exactly the one the platform will not give.
- **Every storage volume is separate.** An SD card, a USB drive, or any other volume needs its own
  pick, so the prompt repeats per volume, and a tree the user has not picked stays invisible to
  operations that span the device, such as a recursive search or a cross-volume move.
- **Cross-tree operations are unreliable over SAF.** `moveDocument()` is optional and
  document-provider-dependent, so moving files or folders between trees can fail outright, which
  a file manager cannot present to the user as a working feature.
- **No symlink support.** SAF cannot read or create symbolic links; these operations throw on
  SAF paths. Direct file access is required to handle symlinks.
- **In-place editing of arbitrary files.** Butler's editor reads and writes files at any path the
  user navigates to. SAF only permits this for documents the user has individually picked, not for
  the free in-place editing a file manager provides.

Butler uses SAF where it is sufficient and requests All-files access only for the
file-management functionality above, which SAF and MediaStore cannot provide.

---

## 4. Demo video script

This is the flow the automated recorder produces (`./record.sh`); see
[`../README.md`](../README.md#generating-the-demo-videos-automated). Length **~104s**, portrait
720×1606, synthetic data only.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — All files access (MANAGE_EXTERNAL_STORAGE)". |
| 2 | **Feature blocked without it** | Open an Explorer workspace, try to browse `/storage/emulated/0` → in-context "Permission needed / Storage access" card. |
| 3 | **Grant the permission** | Butler's Permission-setup screen (its rationale text) → Android's "Allow access to manage all files" system toggle → ON → back; storage now lists. |
| 4 | **Browse non-media folders** | Navigate into `Projects/butler-notes/` showing non-media files: `build.log`, `config.json`, `README.md`. |
| 5 | **Manage non-media files** | Multi-select all three → Cut → navigate to a different directory (`Documents`) → Paste. Bulk move of logs/configs/docs across the tree. |
| 6 | **Search the whole device** | Open a Search workspace and search `report` across `/storage/emulated/0`. One query traverses the whole volume from a single permission and returns hits in unrelated folders: `Annual-report.pdf` in `Documents/Work` and `Quarterly-report.pdf` in `Download`, each with its full path. Over SAF the user would have to pick each of those trees, and Android 11+ refuses a grant on the storage root that would otherwise cover both at once. |
| 7 | **Tabbed workspaces** | Open the Tab Manager, showing live workspace previews of **both** tabs: the Explorer (with the moved files) and the Searcher (with the search results). |
| 8 | **End card** | "Browse and manage arbitrary files and folders across the device, in tabbed workspaces. On-device only — never uploaded." |

Notes:
- Deliberately uses **non-media** files in a deep arbitrary folder (not photos/PDFs in
  `Documents`), to make plain that MediaStore/SAF cannot serve the feature.
- The three core pillars are all shown: arbitrary non-media browse (against MediaStore), bulk move
  across directories (against single-file SAF picking), and whole-volume traversal from a single
  permission (against picking every tree separately in the SAF picker). In-place editing (§2, §3)
  is described but not filmed, to keep length down.
- No audio; on-screen captions only.
- The `QUERY_ALL_PACKAGES` demo is a separate video (see that folder).

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the All files access permission (MANAGE_EXTERNAL_STORAGE).

1. The app tries to browse internal shared storage and shows a "Permission needed" card instead of a file list. Without the permission there is nothing to manage.
2. Butler's permission setup screen explains the request, then Android's own "Allow access to manage all files" toggle is switched on. Back in Butler, storage lists.
3. A deep folder, Projects/butler-notes, is opened. It holds non-media files: build.log, config.json and README.md.
4. All three files are multi-selected, cut, and pasted into another directory. Bulk management of non-media files across directories is the feature that needs the permission.
5. A search workspace searches for "report" across all of internal shared storage. The single query traverses the whole volume and returns Annual-report.pdf in Documents/Work and Quarterly-report.pdf in Download, each with its full path.
6. The tab manager shows both workspaces at once, the explorer with the moved files and the searcher with its results.

Everything shown happens on the device. File names and file contents are not collected and are not used for analytics or advertising; a file leaves Butler only when the user shares it to another app, or inside a debug log or crash report the user chooses to share.
```
