# Butler Recycle Bin Implementation Plan

## Overview

Implement a recycle bin feature that moves deleted files to per-storage trash folders instead of permanently deleting them. Users access a unified view of all deleted items via a "Recycle Bin" shortcut on the home screen.

## Architecture Decisions

### Storage Location

- **Physical paths:** `Android/data/eu.darken.butler/cache/.recyclebin/` per storage root
    - Internal: `/sdcard/Android/data/eu.darken.butler/cache/.recyclebin/`
    - SD Card: `/storage/XXXX-XXXX/Android/data/eu.darken.butler/cache/.recyclebin/`
- **Note:** Cache folders may be cleared by Android under storage pressure (acceptable trade-off)

### UI Access (following industry pattern)

- **Unified approach:** Single "Recycle Bin" shortcut on home screen (next to "Device")
- Shows aggregated view of deleted items from ALL storages
- Items are grouped/labeled by their source storage
- No per-storage recycle bin entries (matches Files by Google, Solid Explorer, MiXplorer pattern)

### Metadata Storage

- **Room database** with schema:
  ```kotlin
  RecycleBinItem(
    id: String,
    originalPath: String,
    recycleBinPath: String,
    deletedAt: Instant,
    size: Long,
    storageRootId: String,  // Identifies which storage
    mimeType: String?
  )
  ```

### Failure Handling

- If move to recycle bin fails → fallback to direct delete with user confirmation

---

## Implementation Phases

### Phase 1: Core Infrastructure (app-common-io module)

#### 1.1 Settings & Database

**New files:**

- `app-common-io/.../recyclebin/RecycleBinSettings.kt`
    - DataStore settings: enabled (default: true), autoDeleteDays (default: 30), maxSizePerStorageMB (default: 500)
- `app-common-io/.../recyclebin/RecycleBinDatabase.kt`
    - Room database with `RecycleBinItem` entity
    - DAO with queries: getAll(), getByStorage(), getOlderThan(), delete()
- `app-common-io/.../recyclebin/RecycleBinItem.kt`
    - Data class for deleted item metadata

#### 1.2 Recycle Bin Manager

**New file:** `app-common-io/.../recyclebin/RecycleBinManager.kt`

- Core operations:
    - `getRecycleBinPath(originalPath: APath<*>): APath<*>` - determines bin path for storage
    - `moveToRecycleBin(paths: List<APath<*>>): Result` - moves files and records metadata
    - `restore(items: List<RecycleBinItem>): Result` - moves files back to original locations
    - `deletePermanently(items: List<RecycleBinItem>): Result` - actual deletion
    - `emptyRecycleBin(storageId: String? = null): Result` - clears bin (all or per-storage)
    - `getSize(storageId: String? = null): Long` - calculates total size
    - `cleanupExpired()` - removes items older than autoDeleteDays

#### 1.3 Repository

**New file:** `app-common-io/.../recyclebin/RecycleBinRepository.kt`

- Combines database queries with file system operations
- Handles storage volume ID mapping
- Syncs database with actual file system state

---

### Phase 2: Delete Operation Interception (app-workspace-explorer)

#### 2.1 Modify Delete Operation

**File:** `app-workspace-explorer/.../operations/DeleteOperation.kt`

**Changes:**

- Before calling `GenericPathDelete`, check `recycleBinSettings.enabled`
- If enabled:
    - Try `recycleBinManager.moveToRecycleBin(targets)`
    - On success: return operation report with "Moved to Recycle Bin" message
    - On failure: show confirmation dialog → fallback to direct delete
- If disabled: proceed with normal delete

#### 2.2 Create Recycle Bin Operations

**New files:**

- `app-workspace-explorer/.../operations/RecycleBinRestoreOperation.kt`
    - Uses `GenericPathMove` to restore files to original locations
    - Handles conflicts (file already exists at original path)
- `app-workspace-explorer/.../operations/RecycleBinEmptyOperation.kt`
    - Uses `GenericPathDelete` to permanently delete
    - Cleans up database entries

---

### Phase 3: Navigation & UI (app-workspace-explorer)

#### 3.1 Add Navigation Target

**File:** `app-workspace-explorer/.../ExplorerNavigation.kt`

**Add:**

```kotlin
data object RecycleBin : Target {
    override val label: CaString = R.string.explorer_navigation_recyclebin.toCaString()
    override val description: CaString = R.string.explorer_navigation_recyclebin_desc.toCaString()
}
```

#### 3.2 Add Home Shortcut

**File:** `app-workspace-explorer/.../engine/HomeLocationLoader.kt`

**Modify:** Add recycle bin shortcut to shortcuts list (line 47-55):

```kotlin
ExplorerItem.Shortcut(
    shortcutId = "recyclebin",
    displayIcon = Icons.TwoTone.Delete,  // or RestoreFromTrash
    displayName = R.string.explorer_navigation_recyclebin.toCaString(),
    target = ExplorerNavigation.Target.RecycleBin,
    subtitle = caString { /* Show total size and item count */ },
)
```

#### 3.3 Create Recycle Bin Location Loader

**New file:** `app-workspace-explorer/.../engine/RecycleBinLocationLoader.kt`

- Loads items from all storages via `RecycleBinRepository`
- Groups items by storage root with headers
- Provides bulk operations: restore selected, delete permanently, empty bin
- Shows item metadata: original path, deleted date, size
- Progress tracking for large operations

#### 3.4 Recycle Bin UI

**New file:** `app-workspace-explorer/.../ui/RecycleBinLocationContent.kt`

- Displays recycled items in list/grid
- Shows storage source badge per item
- Action buttons: Restore, Delete Permanently, Empty Bin
- Filter/sort options: by date, by size, by storage

---

### Phase 4: Settings UI (app module)

#### 4.1 Recycle Bin Settings Screen

**New files:**

- `app/src/.../settings/recyclebin/RecycleBinSettingsViewModel.kt`
    - Exposes settings flow, current size, item count per storage
    - Actions: toggle enabled, set retention days, set max size, empty bin
- `app/src/.../settings/recyclebin/RecycleBinSettingsScreen.kt`
    - Enable/disable toggle
    - Auto-delete after X days slider (7-90 days)
    - Max size per storage slider (100MB-2GB)
    - Size display per storage with "Clear" buttons
    - Total size and "Empty All Recycle Bins" button with confirmation
- `app/src/.../settings/recyclebin/RecycleBinSettingsDestination.kt`
    - Navigation destination

#### 4.2 Link from Storage Settings

**File:** `app/src/.../settings/storage/StorageSettingsScreen.kt`

**Add:** New settings category:

```kotlin
SettingsCategoryHeader(R.string.settings_storage_recyclebin_header)
SettingsPreferenceItem(
    title = R.string.settings_storage_recyclebin_title,
    subtitle = /* Show total size */,
    onClick = { /* Navigate to RecycleBinSettingsScreen */ }
)
```

---

### Phase 5: Background Cleanup

#### 5.1 Scheduled Cleanup Worker

**New file:** `app/src/.../common/recyclebin/RecycleBinCleanupWorker.kt`

- WorkManager periodic worker (runs daily)
- Calls `recycleBinManager.cleanupExpired()`
- Respects autoDeleteDays setting
- Logs cleanup statistics

#### 5.2 Register Worker

**File:** `app/src/.../App.kt` or setup module

- Schedule periodic cleanup on app initialization

---

## String Resources

**Add to `app-common/res/values/strings.xml`:**

```xml

<string name="common_recyclebin_label">Recycle Bin</string><string name="common_recyclebin_empty">Empty Recycle Bin
</string><string name="common_recyclebin_restore">Restore</string><string name="common_recyclebin_delete_permanently">
Delete Permanently
</string>
```

**Add to `app-workspace-explorer/res/values/strings.xml`:**

```xml

<string name="explorer_navigation_recyclebin">Recycle Bin</string><string name="explorer_navigation_recyclebin_desc">
Recover deleted files
</string><string name="explorer_recyclebin_empty_state">No deleted items</string><string
name="explorer_recyclebin_item_count">%d items
</string><string name="explorer_recyclebin_storage_header">%s storage</string>
```

**Add to `app/res/values/strings.xml`:**

```xml

<string name="settings_storage_recyclebin_header">Recycle Bin</string><string name="settings_storage_recyclebin_title">
Manage Recycle Bin
</string><string name="settings_recyclebin_enabled">Enable Recycle Bin</string><string
name="settings_recyclebin_auto_delete">Auto-delete after %d days
</string><string name="settings_recyclebin_max_size">Max size per storage: %d MB</string><string
name="settings_recyclebin_current_size">Current size: %s
</string><string name="settings_recyclebin_empty_confirm">Permanently delete all items?</string>
```

---

## Research: How Other File Managers Handle Recycle Bins

### UX Pattern (Unified View)

All major Android file managers use a **single, unified trash view** accessible from navigation:

- **Files by Google:** Side menu → "Trash"
- **Solid Explorer:** Left sidebar → "Trash"
- **MiXplorer:** Hamburger menu → "Recycle Bin"

**Why unified works:**

- Users check one place, not multiple locations per storage
- Can filter/search across all deleted items
- Simpler mental model
- App internally manages per-storage physical folders

### Physical Storage Paths Used

| File Manager               | Trash Folder Path                                      | Visibility             |
|----------------------------|--------------------------------------------------------|------------------------|
| **MiXplorer**              | `/sdcard/.recycle`                                     | Hidden (dot prefix)    |
| **Files by Google**        | `/sdcard/.FilesByGoogleTrash`                          | Hidden (dot prefix)    |
| **X-plore**                | `/sdcard/Android/data/com.lonelycatgames.Xplore/trash` | App-private folder     |
| **ES File Explorer**       | `/sdcard/.esFileExplorerTrash`                         | Hidden (dot prefix)    |
| **Android 11+ MediaStore** | `/sdcard/.$Trash$`                                     | Hidden, system-managed |

### Common Implementation Patterns

**Retention Policies:**

- **30 days** is the industry standard (Files by Google, FX File Explorer, Android MediaStore)
- Automatic deletion after retention period
- Manual emptying available

**Scope Limitations:**

- Most apps only track files deleted within their own app
- Android 11+ MediaStore API provides system-wide trash for media files only

**Storage Approach:**

- **Hidden folders** (dot prefix): `/sdcard/.AppNameTrash`
- **App-private folders:** `/Android/data/[package]/files/trash` or `/cache/trash`
- **Per-storage bins:** Separate trash folder per storage volume (internal, SD cards)

---

## Testing Strategy

1. **Unit tests:** RecycleBinManager operations, database queries
2. **Integration tests:** Delete → move to bin → restore flow
3. **Manual testing scenarios:**
    - Delete from internal storage, restore
    - Delete from SD card, restore
    - Delete large file (>100MB)
    - Delete with recycle bin disabled
    - Delete when bin is full (exceeds max size)
    - Empty bin
    - Auto-cleanup after retention period
    - Cross-storage operations

---

## Migration & Backwards Compatibility

- No database migration needed (new database)
- Existing users: recycle bin disabled by default, show onboarding toast/dialog
- Settings migration: none required

---

## Future Enhancements (Out of Scope)

- Search within recycle bin
- Undo for empty bin operation
- Cloud storage recycle bins (Google Drive, Dropbox)
- Encrypted recycle bin for sensitive files