# Declaration — All files access (`MANAGE_EXTERNAL_STORAGE`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **All files access**

## 1. Permitted use to select

**File management.**

Google's permitted-use wording (file manager), which Butler matches exactly:

> "App's core purpose involves the access, editing, and management (including maintenance) of
> files and folders outside of its app-specific storage space."

---

## 2. "How does your app use this permission?" (paste into the form)

Butler is a general-purpose file manager. Its core, primary purpose is to let the user access,
edit, and manage — including maintenance — files and folders across the device's shared storage,
outside the app's own app-specific directory.

Features that depend on broad file access, all of which are the app's main, prominently
advertised functionality:

- Browsing the entire shared-storage tree and operating on files of **any type** — not only
  media, but non-media files such as logs, configuration files, source code, archives, and
  databases that the MediaStore API does not expose at all.
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
file listings are never collected, transmitted, sold, or shared. (See the app's privacy policy.)

This is **not** manual, single-file selection — it is continuous management and maintenance of
whole directory trees the user navigates, which the Storage Access Framework file picker cannot
provide (see below).

---

## 3. "Why is an alternative API not sufficient?" (paste into the form)

Butler cannot deliver its core file-management functionality with the Storage Access Framework
(SAF) or the MediaStore API:

- **MediaStore only exposes media files.** A file manager must manage *all* file types — logs,
  configuration files, source code, archives, databases, app exports — not just images, audio,
  and video. MediaStore cannot see or modify these at all, so it cannot serve the core feature.
- **Whole-volume management vs. per-folder grants.** SAF grants access one directory tree at a
  time via the system picker. A file manager must let the user freely traverse and operate on the
  entire shared volume; requiring a separate manual SAF grant for every folder makes general file
  management impractical, and is exactly the "manual file selection" pattern SAF is meant for —
  not whole-device management.
- **Recursive search needs unrestricted traversal.** Searching across the whole tree — by file
  name and by file contents — requires walking every subfolder; SAF would demand a separate
  explicit grant for each branch, which defeats the feature.
- **In-place editing of arbitrary files.** Butler's editor reads and writes files at any path the
  user navigates to. SAF only permits this for documents the user has individually picked, not for
  the free in-place editing a file manager provides.
- **Bulk/atomic operations are unreliable over SAF.** SAF's `moveDocument()` is optional and
  document-provider-dependent, so atomic move/delete of files and folders across arbitrary trees
  may fail. Direct access provides consistent, performant bulk copy/move/delete.
- **No symlink support.** SAF cannot read or create symbolic links; these operations throw on
  SAF paths. Direct file access is required to handle symlinks.

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
| 6 | **Search the whole device** | Open a Search workspace and search `report` across `/storage/emulated/0`. One query returns hits from unrelated folders — `Annual-report.pdf` in `Documents/Work` and `Quarterly-report.pdf` in `Download` — each with its full path. A per-folder SAF grant could not span both. |
| 7 | **Tabbed workspaces** | Open the Tab Manager, showing live workspace previews of **both** tabs: the Explorer (with the moved files) and the Searcher (with the search results). |
| 8 | **End card** | "Browse and manage arbitrary files and folders across the device, in tabbed workspaces. On-device only — never uploaded." |

Notes:
- Deliberately uses **non-media** files in a deep arbitrary folder (not photos/PDFs in
  `Documents`), to make plain that MediaStore/SAF cannot serve the feature.
- The three core pillars are all shown: arbitrary non-media browse (vs. MediaStore), bulk
  move across trees (vs. single-file SAF picking), and whole-volume recursive search (vs.
  per-folder SAF grants). In-place editing (§2–3) is described but not filmed, to keep length down.
- No audio; on-screen captions only.
- The `QUERY_ALL_PACKAGES` demo is a separate video (see that folder).
