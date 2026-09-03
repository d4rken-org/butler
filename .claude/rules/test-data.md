# Generating Test Data

Butler generates its own test files. Do NOT write a shell script that pushes files onto a device
with `adb push`, `dd`, or a `for` loop. The Developer workspace already does nested trees, large
files, high file counts, and fake operation history, and it does it through the gateway system so
the result matches what a real user's storage looks like.

## Reaching the generator

Developer Tools is a normal workspace type: **Create tab, Developer Tools, then the "Test Data" tab**.

The tile only appears while developer mode is unlocked. `DeveloperSettings.isDeveloperModeUnlocked`
defaults to `BuildConfigWrap.DEBUG`, so on a debug build it is already unlocked and no gesture is
needed. On a release build, unlock it with 7 long-presses on the changelog/version row in Settings.

## Target paths

The page pre-populates one entry per detected storage volume (`/storage/emulated/0`, SD cards).
"Add path" opens the directory picker in multi-select mode, restricted to writable directories, so
SAF trees and other gateway path types work as targets too. Every enabled generator runs once per
listed path.

## Grant all-files access first

Without it every generator fails with `Permission denied: Cannot access "aButlerTextFiles" at
"/storage/emulated/0" due to insufficient permissions.` On an emulator, skip the UI flow:

```bash
adb -s <serial> shell appops set --uid eu.darken.butler MANAGE_EXTERNAL_STORAGE allow
```

## What each option produces

Each generator writes into its own folder under the target path, so the three are independent and
re-running one overwrites only its own files.

| Option | Folder | Actual result |
|--------|--------|---------------|
| Large files | `aButlerLargeFiles/` | 7 sparse files, `file_1MB.bin` to `file_8GB.bin`, 15.1 GiB of reported size in 64 KB of actual blocks |
| Nested structure | `aButlerNestedData/` | 1092 folders, 6 levels deep, 3 folders per level; 1092 text files (1 KB to 50 KB each), ~41 MB |
| Text files for Editor | `aButlerTextFiles/` | `text_10KB.txt` to `text_100MB.txt`, numbered lorem lines, ~117 MB |

The `.bin` files are sparse: each reports its full nominal size but occupies almost no blocks, so
the set generates in about a second on a device with nowhere near 15 GB free. Reading one back
yields zeros, so use them for anything that goes by file size (listing, sorting, size warnings,
copy estimates) and the `aButlerTextFiles/` set for anything that reads content.

The nested tree's deepest level gets folders but no files, which is why the folder and file counts
match.

## While it runs

Generators are regular `Operation`s: they show progress in the operations bar, run concurrently
across the enabled options, are cancellable mid-run, and land in the History workspace on
completion. That makes them useful as long-running operations to test progress and cancel UI, not
just as a source of files.

## Sample history entries

The same tab has a "Generate sample history" button that inserts ~25 fake operation rows spanning
the last 30 days with mixed kinds, outcomes and origins. Use it instead of performing 25 real file
operations to populate the History workspace. It writes through the DAO directly, so the generator
itself does not appear in the list.

## Cleanup

```bash
adb -s <serial> shell rm -rf /storage/emulated/0/aButler{LargeFiles,NestedData,TextFiles}
```
