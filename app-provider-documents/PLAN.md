# DocumentsProvider Implementation Plan

## Executive Summary

This module implements Android's `DocumentsProvider` API to expose Butler's file access capabilities to other apps through the system file picker. This enables users to select Butler-managed files (including root and ADB-accessible locations) when uploading files in browsers, selecting attachments in email clients, or any app using Android's Storage Access Framework (SAF).

**Status**: Planning phase
**Priority**: Medium - Enhances Butler's integration with Android ecosystem
**Complexity**: Moderate - Leverages existing infrastructure with careful API implementation

---

## Goals & Motivation

### Primary Use Cases
1. **Browser File Uploads**: Select files from Butler when uploading to web services (e.g., EDMS-NG document management)
2. **Email Attachments**: Attach files via Butler in email clients
3. **Inter-App File Sharing**: Make Butler files accessible to all apps using SAF file picker
4. **Advanced Access**: Expose root and ADB-accessible files to apps that normally can't access them

### User Value
- **Unified File Access**: One file picker shows all Butler-accessible storage locations
- **Advanced Capabilities**: Share files from restricted locations (Android/data, system directories) with apps
- **Seamless Integration**: Butler appears alongside other document providers (Google Drive, Downloads, etc.)

### Strategic Value
- Positions Butler as **system-level file management solution**
- Differentiates from basic file explorers
- Enables workflows impossible with standard file pickers

---

## Background: DocumentsProvider API

### What is DocumentsProvider?

`DocumentsProvider` is Android's API (introduced in KitKat 4.4) that allows apps to expose their storage to the system's unified document picker. It's the **provider side** of the Storage Access Framework (SAF).

**Butler's Current SAF Usage**:
- Butler is a SAF **client** (consumer) - uses SAF to access restricted directories
- `/app-common-io/src/main/java/eu/darken/butler/common/files/saf/` contains SAF client code
- Now we're implementing the **provider side** to expose Butler's files to other apps

### Core Concepts

**Roots**:
- Top-level storage locations shown in the picker drawer
- Example roots: "Internal Storage", "SD Card", "Butler - Root Access"
- Each root has an icon, title, and capabilities flags

**Documents**:
- Individual files or directories within a root
- Each has a stable, unique Document ID
- Metadata includes: display name, MIME type, size, modification date, flags

**Document IDs**:
- Opaque strings that uniquely identify documents
- **Must be stable** - cannot change once returned (except during rename)
- Provider-internal format, not exposed to clients
- Used to reference documents across operations

**Content URIs**:
- Format: `content://{authority}/document/{documentId}`
- System grants temporary URI permissions to calling apps
- Permissions automatically managed by framework

### Key API Methods

**Required for Basic Read-Only Support**:
```kotlin
// Return available storage roots
queryRoots(projection: String[]): Cursor

// Return metadata for a single document
queryDocument(documentId: String, projection: String[]): Cursor

// List immediate children of a directory
queryChildDocuments(parentDocId: String, projection: String[], sortOrder: String?): Cursor

// Open file for reading/writing
openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor
```

**Optional for Write Support** (Future):
```kotlin
createDocument(parentDocId: String, mimeType: String, displayName: String): String
deleteDocument(documentId: String)
renameDocument(documentId: String, displayName: String): String?
copyDocument(sourceDocId: String, targetParentDocId: String): String
moveDocument(sourceDocId: String, sourceParentDocId: String, targetParentDocId: String): String
```

**Optional Advanced Features** (Future):
```kotlin
queryRecentDocuments(rootId: String, projection: String[]): Cursor
querySearchDocuments(rootId: String, query: String, projection: String[]): Cursor
openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor
isChildDocument(parentDocId: String, documentId: String): Boolean  // Required for tree access
```

### Critical Requirements

1. **Document ID Stability**: IDs must never change (except rename) - violating this breaks client apps
2. **Thread Safety**: All methods called from Binder thread pool - must handle concurrent access
3. **Performance**: Metadata queries must be fast - no blocking network calls
4. **Permission Revocation**: Must call `revokeDocumentPermission()` after deleting documents
5. **Security**: Manifest must declare `android.permission.MANAGE_DOCUMENTS` to restrict access to system only

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────┐
│                  System Documents UI                     │
│              (or any app using SAF picker)               │
└────────────────────┬────────────────────────────────────┘
                     │ Content URI requests
                     ▼
┌─────────────────────────────────────────────────────────┐
│            ButlerDocumentsProvider                       │
│  ┌─────────────────────────────────────────────────┐   │
│  │  queryRoots()          ─────►  RootManager       │   │
│  │  queryDocument()       ─────►  DocumentQuery     │   │
│  │  queryChildDocuments() ─────►  DocumentQuery     │   │
│  │  openDocument()        ─────►  DocumentIdEncoder │   │
│  └─────────────────┬───────────────────────────────┘   │
└────────────────────┼───────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              DocumentIdEncoder                           │
│         (Encode/decode APath ↔ Document ID)             │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                GatewaySwitch                             │
│         (Routes to appropriate gateway)                  │
│  ┌──────────────┬──────────────┬──────────────┐        │
│  │ LocalGateway │ RootGateway  │  ADBGateway  │        │
│  └──────────────┴──────────────┴──────────────┘        │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
               Actual Files
    (Internal, SD Card, Root paths, ADB paths)
```

### Integration with Existing Butler Architecture

**Reuse, Don't Reimplement**:

Butler already has a sophisticated file access architecture. DocumentsProvider will be a **thin translation layer** between Android's DocumentsProvider API and Butler's existing infrastructure.

**Key Existing Components to Leverage**:

1. **APath System** (`app-common-io/APath.kt`)
   - Abstract path representation supporting multiple storage types
   - Implementations: `LocalPath`, `SAFPath`
   - Future: `RootPath`, `ADBPath` (if not already existing)
   - **Usage**: Document IDs will encode `APath` instances

2. **Gateway Pattern** (`app-common-io/GatewaySwitch.kt`)
   - Routes file operations to appropriate implementation
   - `LocalGateway`: Standard file access
   - `RootGateway`: Root-based access (presumably in `app-common-root`)
   - `ADBGateway`: Shizuku/ADB access (presumably in `app-common-adb`)
   - **Usage**: All file operations route through existing gateways

3. **APathLookup** (`app-common-io/APathLookup.kt`)
   - Contains file metadata (size, type, modified date, permissions)
   - Already optimized for performance
   - **Usage**: Direct mapping to DocumentsContract.Document columns

4. **FileSystemOps** (`app-common-io/FileSystemOps.kt`)
   - Core file operations interface
   - Implementations already handle edge cases and errors
   - **Usage**: Create/delete/rename operations (Phase 3)

5. **MimeInfo** (`app-common-io/MimeInfo.kt`)
   - MIME type detection logic
   - **Usage**: Populate `COLUMN_MIME_TYPE`

**Why This Approach is Powerful**:
- Zero duplication of file access logic
- Automatic inheritance of Butler's permission handling, error recovery, and optimizations
- Thin provider layer (~500-800 lines total) with all complexity delegated to proven components
- Easy maintenance - improvements to gateways automatically benefit provider

### Module Structure

```
app-provider-documents/
├── build.gradle.kts
├── PLAN.md (this file)
├── src/main/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── drawable/
│   │   │   ├── ic_root_internal_storage.xml
│   │   │   ├── ic_root_sd_card.xml
│   │   │   ├── ic_root_root_access.xml
│   │   │   └── ic_root_adb_access.xml
│   │   └── values/
│   │       └── strings.xml
│   └── java/eu/darken/butler/provider/documents/
│       ├── ButlerDocumentsProvider.kt        # Main ContentProvider
│       ├── DocumentIdCodec.kt                # Encode/decode Document IDs ↔ APath
│       ├── DocumentsProviderModule.kt        # Hilt DI setup
│       │
│       ├── roots/
│       │   ├── DocumentRoot.kt               # Sealed class hierarchy for root types
│       │   ├── RootManager.kt                # Manages available roots, dynamic visibility
│       │   └── RootVisibilityMonitor.kt      # Monitors permission changes (future)
│       │
│       ├── query/
│       │   ├── DocumentQueryHandler.kt       # Handles queryDocument/queryChildDocuments
│       │   └── RootQueryHandler.kt           # Handles queryRoots
│       │
│       ├── operations/
│       │   ├── DocumentReader.kt             # Handles openDocument (read-only)
│       │   ├── DocumentCreator.kt            # Handles createDocument (Phase 3)
│       │   ├── DocumentModifier.kt           # Handles rename/delete (Phase 3)
│       │   └── DocumentMover.kt              # Handles copy/move (Phase 3)
│       │
│       └── settings/
│           ├── DocumentsProviderSettings.kt  # DataStore for user preferences
│           └── ProviderPreferences.kt        # Serializable preferences data class
│
└── src/test/
    └── java/eu/darken/butler/provider/documents/
        ├── DocumentIdCodecTest.kt
        └── RootManagerTest.kt
```

### Document ID Design

Document IDs are the **core stability requirement** of DocumentsProvider. Once returned, they must never change (except during rename).

**Format**: `{pathType}|{base64EncodedPathData}`

**Components**:
- `pathType`: Identifies the APath implementation (`local`, `saf`, future: `ssh`, `ftp`)
- `base64EncodedPathData`: URL-safe Base64-encoded path data (path string for LocalPath, JSON for SAFPath)

**Examples**:
```kotlin
// Internal storage file - LocalPath
"local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9Eb3dubG9hZC9maWxlLnBkZg"
// Decodes to: LocalPath("/storage/emulated/0/Download/file.pdf")

// System file (root access transparent via GatewaySwitch) - LocalPath
"local|L3N5c3RlbS9idWlsZC5wcm9w"
// Decodes to: LocalPath("/system/build.prop")
// Note: GatewaySwitch automatically uses RootGateway for /system paths

// Android/data file (ADB access transparent via GatewaySwitch) - LocalPath
"local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9BbmRyb2lkL2RhdGEvZmlsZS50eHQ"
// Decodes to: LocalPath("/storage/emulated/0/Android/data/file.txt")
// Note: GatewaySwitch automatically uses ADBGateway for Android/data paths

// SAF tree file - SAFPath (JSON-serialized)
"saf|eyJ0cmVlUm9vdCI6ImNvbnRlbnQ6Ly8uLi4iLCJzZWdtZW50cyI6WyJmb2xkZXIiLCJmaWxlLnR4dCJdfQ=="
// Decodes to: SAFPath(treeRoot="content://...", segments=["folder", "file.txt"])

// Future: SSH path
"ssh|eyJzZXJ2ZXJJZCI6IjEyMyIsInBhdGgiOiIvaG9tZS91c2VyL2ZpbGUudHh0In0="
// Decodes to: SSHPath(serverId="123", path="/home/user/file.txt")
```

**Why This Design**:
- ✅ **Simple**: Only 2 components (was 3 before)
- ✅ **Stable**: Absolute paths won't change unless file moves
- ✅ **Unique**: Each path on the system is globally unique
- ✅ **Reversible**: Can reconstruct exact `APath` from Document ID
- ✅ **Safe**: Base64 URL-safe encoding handles special characters, separators
- ✅ **Extensible**: Easy to add new path types (ssh, ftp, etc.)
- ✅ **No Redundancy**: Path uniquely identifies location - no need for separate rootId

**Why No rootId?**

The path itself is globally unique on the device:
- `/storage/emulated/0/file.txt` can only be one location
- `/storage/1234-5678/file.txt` (SD card) is a different unique location
- SAFPath treeRoot URIs are globally unique

Root identification happens in two places:
1. **In queryRoots()**: Android API requires `COLUMN_ROOT_ID` (e.g., "device_internal") - this is metadata, not part of document IDs
2. **For lookups**: Can infer root from path if needed (e.g., `/storage/emulated/0` → Internal Storage root)

**Implementation**:
```kotlin
object DocumentIdCodec {
    private const val SEPARATOR = "|"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(path: APath<*>): String {
        val pathType = when (path) {
            is LocalPath -> "local"
            is SAFPath -> "saf"
            // Future: is SSHPath -> "ssh", is FTPPath -> "ftp"
            else -> throw IllegalArgumentException("Unsupported path type: ${path::class}")
        }

        val encodedData = when (path) {
            is LocalPath -> {
                // Simple: encode the absolute path string
                Base64.encodeToString(
                    path.path.toByteArray(),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )
            }
            is SAFPath -> {
                // Complex: JSON-serialize the entire SAFPath object (treeRoot + segments)
                val jsonString = json.encodeToString(path)
                Base64.encodeToString(
                    jsonString.toByteArray(),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )
            }
            else -> throw IllegalArgumentException("Unsupported path type")
        }

        return "$pathType$SEPARATOR$encodedData"
    }

    fun decode(documentId: String): APath<*> {
        val parts = documentId.split(SEPARATOR)
        require(parts.size == 2) { "Invalid document ID format: expected 2 parts, got ${parts.size}" }

        val (pathType, encodedData) = parts
        val decodedBytes = Base64.decode(encodedData, Base64.URL_SAFE)

        return when (pathType) {
            "local" -> {
                val pathString = String(decodedBytes)
                LocalPath.build(pathString)
            }
            "saf" -> {
                val jsonString = String(decodedBytes)
                json.decodeFromString<SAFPath>(jsonString)
            }
            // Future: "ssh" -> json.decodeFromString<SSHPath>(String(decodedBytes))
            else -> throw IllegalArgumentException("Unknown path type: $pathType")
        }
    }
}
```

**Edge Cases Handled**:
- **Special characters in paths**: Base64 encoding handles all characters safely
- **Very long paths**: No length limit on Document IDs (Base64 is ~33% larger, but even 500-char paths → ~670-char IDs)
- **SAFPath complexity**: JSON serialization preserves both treeRoot and segments
- **Symbolic links**: Encode the link path itself, not target
- **Renamed files**: renameDocument can return new ID with updated path
- **Unicode**: UTF-8 encoding before Base64 handles all Unicode characters

**What About Renames?**:
When a file is renamed, `renameDocument()` is allowed to return a **new Document ID**. This is the one exception to ID stability. Our implementation:
1. Perform rename via gateway
2. Return new Document ID with updated path
3. System handles old → new ID migration for clients

---

### Path Types vs Access Methods vs Roots

**Critical Architectural Distinction**: There are three separate concepts that must not be confused:

#### 1. Path Types (Data Structures)

These are **APath implementations** - data structures representing file locations:

| Path Type | Class | Description | Encoded in Document ID |
|-----------|-------|-------------|------------------------|
| `local` | `LocalPath` | Local filesystem paths | ✅ Yes |
| `saf` | `SAFPath` | Storage Access Framework paths | ✅ Yes |
| `ssh` (future) | `SSHPath` | SSH/SFTP remote paths | ✅ Yes |
| `ftp` (future) | `FTPPath` | FTP remote paths | ✅ Yes |

**Examples:**
```kotlin
LocalPath.build("/storage/emulated/0/Download/file.pdf")  // local path type
SAFPath(treeRoot="content://...", segments=["file.txt"])   // saf path type
```

**In Document IDs:** `pathType` field identifies which APath implementation

#### 2. Access Methods (Gateways - Internal Implementation)

These are **how Butler reads files** - internal implementation details that clients never see:

| Gateway | Purpose | When Used |
|---------|---------|-----------|
| `LocalGateway` | Normal file access | Most `/storage/` paths |
| `RootGateway` | Root-privileged access | System paths like `/system`, `/data` |
| `ADBGateway` | Shizuku/ADB access | Restricted paths like `/Android/data` |
| `SAFGateway` | SAF framework access | SAF paths |

**Path-Based Routing (via GatewaySwitch):**
```kotlin
// GatewaySwitch automatically selects gateway based on path:
val path = LocalPath.build("/system/build.prop")
gatewaySwitch.lookup(path, options)  // Routes to RootGateway internally

val path2 = LocalPath.build("/storage/emulated/0/Android/data/file.txt")
gatewaySwitch.lookup(path2, options)  // Routes to ADBGateway internally
```

**Key Point:** Access method is **inferred from the path** - not encoded anywhere!

**In Document IDs:** ❌ NOT included - internal implementation detail

#### 3. Roots (Picker Drawer Entries - User-Facing)

These are **what users see in the file picker** - entries in the picker drawer:

| Root | Starting Path | User Sees |
|------|---------------|-----------|
| Internal Storage | `/storage/emulated/0` | "Butler - Internal Storage" |
| SD Card | `/storage/XXXX-XXXX` | "Butler - SD Card" |
| Root Directory (Phase 2+) | `/` | "Butler - Root Directory" |
| System ROM (Phase 2+) | `/system` | "Butler - System ROM" |
| SSH Server (Phase 2+) | `SSHPath(...)` | "Butler - My Server" |

**Represented by:** `DocumentRoot` sealed class (metadata for Android's DocumentsProvider API)

**In Document IDs:** ❌ NOT included - only `COLUMN_ROOT_ID` in queryRoots() response

#### Example: Complete Flow

**User Action:** User opens Chrome, clicks "Upload file", picks "Butler - Internal Storage", navigates to a system file

```
1. Picker shows root: "Butler - Internal Storage"
   - Root metadata: DocumentRoot.InternalStorage
   - Root's apiRootId: "device_internal" (for COLUMN_ROOT_ID)
   - Root's startPath: LocalPath("/storage/emulated/0")

2. User navigates to /system/build.prop
   - Path: LocalPath("/system/build.prop")
   - Path type: local (LocalPath)
   - Document ID: "local|L3N5c3RlbS9idWlsZC5wcm9w"

3. Butler retrieves file metadata:
   - Decode document ID → LocalPath("/system/build.prop")
   - Pass to GatewaySwitch
   - GatewaySwitch sees "/system" prefix → routes to RootGateway
   - Access method: root (transparent to caller)
   - Returns file metadata

4. User selects file:
   - Chrome receives content:// URI with document ID
   - Opens file via openDocument()
   - Same flow: decode → GatewaySwitch → RootGateway → file contents
```

**Key Insight:**
- Path type (`local`) is in document ID
- Access method (root gateway) is internal, inferred from path
- Root ("Internal Storage") is UI metadata, not in document ID

**Why This Matters:**
- ❌ WRONG: Treating "root" and "adb" as path types - they're access methods
- ❌ WRONG: Including rootId in document ID - path is already unique
- ✅ RIGHT: Path types identify data structures, access methods are inferred, roots are UI metadata

---

### Root Configuration

**What are DocumentRoots?**

`DocumentRoot` is **metadata for entries shown in the file picker drawer**. When users open the Android file picker, they see a list of available document providers. Each provider can expose multiple "roots" (storage locations).

**Butler's Roots** (based on Butler's existing architecture):

Phase 1: Device storage locations
- Internal Storage (`/storage/emulated/0`)
- SD Cards (one root per detected card)

Phase 2+: Advanced locations (opt-in via settings)
- Root Directory (`/`) - for browsing entire filesystem
- System ROM (`/system`) - for system files
- SAF Trees (per granted SAF tree)

Phase 3+: Remote servers
- SSH servers (one root per connection)
- FTP servers (one root per connection)

**DocumentRoot Sealed Class**:

```kotlin
sealed class DocumentRoot {
    abstract val apiRootId: String  // For Android's COLUMN_ROOT_ID (not in document IDs!)
    abstract val icon: Int
    abstract val titleRes: Int
    abstract val summaryRes: Int?
    abstract val flags: Int
    abstract val startPath: APath<*>  // Where browsing starts for this root

    // Phase 1: Primary storage - what most users want
    data object InternalStorage : DocumentRoot() {
        override val apiRootId = "device_internal"
        override val icon = R.drawable.ic_phone
        override val titleRes = R.string.documents_root_internal_storage_title  // "Internal Storage"
        override val summaryRes = R.string.documents_root_internal_storage_summary  // "Primary device storage"
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val startPath = LocalPath.build("/storage/emulated/0")
    }

    // Phase 1: SD cards - detected dynamically
    data class SDCard(
        val volumeId: String,  // e.g., "1234-5678"
        val displayName: String?  // User-friendly name if available
    ) : DocumentRoot() {
        override val apiRootId = "device_sd_$volumeId"
        override val icon = R.drawable.ic_sd_card
        override val titleRes = R.string.documents_root_sd_card_title  // "SD Card"
        override val summaryRes = null  // Or use displayName
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY or FLAG_SUPPORTS_EJECT
        override val startPath = LocalPath.build("/storage/$volumeId")
    }

    // Phase 2+: Root filesystem - advanced users (opt-in)
    data object RootDirectory : DocumentRoot() {
        override val apiRootId = "device_root"
        override val icon = R.drawable.ic_folder
        override val titleRes = R.string.documents_root_root_directory_title  // "Root Directory"
        override val summaryRes = R.string.documents_root_root_directory_summary  // "Full filesystem access"
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val startPath = LocalPath.build("/")
        // Note: GatewaySwitch automatically routes /system, /data paths to RootGateway
    }

    // Phase 2+: System partition - advanced users (opt-in)
    data object SystemROM : DocumentRoot() {
        override val apiRootId = "device_system"
        override val icon = R.drawable.ic_system
        override val titleRes = R.string.documents_root_system_rom_title  // "System ROM"
        override val summaryRes = R.string.documents_root_system_rom_summary  // "System partition files"
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val startPath = LocalPath.build("/system")
        // Note: GatewaySwitch automatically routes to RootGateway for /system
    }

    // Phase 2+: SAF trees - per granted tree
    data class SAFTree(
        val treeRootUri: String,  // The SAF tree URI
        val displayName: String  // User-friendly name
    ) : DocumentRoot() {
        override val apiRootId = "saf_${treeRootUri.hashCode()}"
        override val icon = R.drawable.ic_saf
        override val titleRes = 0  // Not used - displayName used instead
        override val summaryRes = null
        override val flags = FLAG_SUPPORTS_IS_CHILD
        override val startPath = SAFPath(treeRootUri, emptyList())
    }

    // Phase 3+: SSH servers
    data class SSHServer(
        val serverId: String,
        val displayName: String,
        val hostName: String
    ) : DocumentRoot() {
        override val apiRootId = "ssh_$serverId"
        override val icon = R.drawable.ic_ssh
        override val titleRes = 0  // Use displayName
        override val summaryRes = null
        override val flags = FLAG_SUPPORTS_IS_CHILD
        // override val startPath = SSHPath(serverId, "/")  // Future
        override val startPath = TODO("SSH not implemented yet")
    }
}
```

**Key Design Notes:**
- **apiRootId**: Used ONLY for Android's `COLUMN_ROOT_ID` in queryRoots() response - NOT embedded in document IDs
- **startPath**: The initial path shown when user selects this root
- **No separate root/ADB roots**: Those are access methods (gateways), not storage locations
- **LocalPath everywhere**: Root/ADB access is transparent - GatewaySwitch routes based on path

**Root Manager** (Singleton):

```kotlin
@Singleton
class RootManager @Inject constructor(
    private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val settings: DocumentsProviderSettings,
    private val storageManager: StorageManager,  // For SD card detection
) {
    suspend fun getAvailableRoots(): List<DocumentRoot> {
        val roots = mutableListOf<DocumentRoot>()

        // Phase 1: Internal storage - always show (if not disabled in settings)
        if (settings.showInternalStorage.value()) {
            roots.add(DocumentRoot.InternalStorage)
        }

        // Phase 1: SD cards - detect dynamically
        if (settings.showExternalStorage.value()) {
            roots.addAll(detectSDCards())
        }

        // Phase 2+: Advanced roots (opt-in)
        if (settings.showRootDirectory.value()) {
            roots.add(DocumentRoot.RootDirectory)
        }

        if (settings.showSystemROM.value()) {
            roots.add(DocumentRoot.SystemROM)
        }

        // Phase 2+: SAF trees
        if (settings.showSAFTrees.value()) {
            roots.addAll(getSAFTrees())
        }

        return roots
    }

    private suspend fun detectSDCards(): List<DocumentRoot.SDCard> {
        // Use Android's StorageManager to enumerate volumes
        val volumes = storageManager.storageVolumes
        return volumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume ->
                volume.uuid?.let { uuid ->
                    DocumentRoot.SDCard(
                        volumeId = uuid,
                        displayName = volume.getDescription(context)
                    )
                }
            }
    }

    private suspend fun getSAFTrees(): List<DocumentRoot.SAFTree> {
        // Get user-granted SAF trees from Butler's settings/database
        // Return one root per tree
        return emptyList()  // TODO: Phase 2
    }

    fun getRootByApiId(apiRootId: String): DocumentRoot? {
        return runBlocking { getAvailableRoots().find { it.apiRootId == apiRootId } }
    }

    fun getRootForPath(path: APath<*>): DocumentRoot? {
        // Infer which root a path belongs to (for lookups)
        return when (path) {
            is LocalPath -> when {
                path.path.startsWith("/storage/emulated/0") -> DocumentRoot.InternalStorage
                path.path.matches(Regex("/storage/[A-F0-9]{4}-[A-F0-9]{4}.*")) -> {
                    val volumeId = path.path.removePrefix("/storage/").substringBefore("/")
                    DocumentRoot.SDCard(volumeId, null)
                }
                path.path == "/" -> DocumentRoot.RootDirectory
                path.path.startsWith("/system") -> DocumentRoot.SystemROM
                else -> null
            }
            is SAFPath -> {
                // Find SAF tree root for this treeRoot
                runBlocking { getSAFTrees().find { it.treeRootUri == path.treeRoot } }
            }
            else -> null
        }
    }
}
```

**Dynamic Root Visibility**:

Phase 1: Roots determined at query time (simple)
Phase 2: Monitor permission changes and notify system to refresh:

```kotlin
class RootVisibilityMonitor @Inject constructor(
    private val context: Context,
    @AppScope private val appScope: CoroutineScope
) {
    fun startMonitoring() {
        // Monitor root availability changes
        // Monitor Shizuku connection changes
        // When changes detected:
        notifyRootsChanged()
    }

    private fun notifyRootsChanged() {
        val uri = DocumentsContract.buildRootsUri(AUTHORITY)
        context.contentResolver.notifyChange(uri, null)
    }
}
```

### Query Implementation

**Root Query Handler**:

```kotlin
class RootQueryHandler @Inject constructor(
    private val context: Context,
    private val rootManager: RootManager
) {
    suspend fun queryRoots(projection: Array<String>?): Cursor {
        log(TAG) { "queryRoots() called" }

        val roots = rootManager.getAvailableRoots()

        val resolvedProjection = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        roots.forEach { root ->
            cursor.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, root.apiRootId)
                add(DocumentsContract.Root.COLUMN_ICON, root.icon)
                add(DocumentsContract.Root.COLUMN_TITLE, context.getString(root.titleRes))
                add(DocumentsContract.Root.COLUMN_SUMMARY, root.summaryRes?.let { context.getString(it) })
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentIdCodec.encode(root.startPath))
                add(DocumentsContract.Root.COLUMN_FLAGS, root.flags)
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, getAvailableBytes(root.startPath))
            }
        }

        log(TAG) { "Returning ${roots.size} roots" }
        return cursor
    }

    private suspend fun getAvailableBytes(path: APath<*>): Long? {
        return try {
            // Use StatFs or gateway to get available space
            // For root access, may need special handling
            TODO("Implement storage space calculation")
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to get available bytes for $path: ${e.asLog()}" }
            null
        }
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
    }
}
```

**Document Query Handler**:

```kotlin
class DocumentQueryHandler @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val rootManager: RootManager
) {
    suspend fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        log(TAG) { "queryDocument($documentId)" }

        val path = DocumentIdCodec.decode(documentId)

        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        try {
            val lookup = gatewaySwitch.lookup(path, LookupOptions())
            cursor.addDocument(documentId, lookup)
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to query document $documentId: ${e.asLog()}" }
            // Return empty cursor - document doesn't exist or not accessible
        }

        return cursor
    }

    suspend fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        log(TAG) { "queryChildDocuments($parentDocumentId, sortOrder=$sortOrder)" }

        val parentPath = DocumentIdCodec.decode(parentDocumentId)

        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        try {
            // List children via gateway
            val children = gatewaySwitch.listFiles(
                parentPath,
                LookupOptions()
            )

            children.forEach { childLookup ->
                val childDocId = DocumentIdCodec.encode(childLookup.path)
                cursor.addDocument(childDocId, childLookup)
            }

            log(TAG) { "Returning ${children.size} children for $parentDocumentId" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to query children of $parentDocumentId: ${e.asLog()}" }
            // Return empty cursor
        }

        return cursor
    }

    private fun MatrixCursor.addDocument(documentId: String, lookup: APathLookup<*>) {
        newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, lookup.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, lookup.mimeType ?: "application/octet-stream")
            add(DocumentsContract.Document.COLUMN_SIZE, lookup.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lookup.modifiedAt?.toEpochMilliseconds())
            add(DocumentsContract.Document.COLUMN_FLAGS, getDocumentFlags(lookup))
        }
    }

    private fun getDocumentFlags(lookup: APathLookup<*>): Int {
        var flags = 0

        // Read-only support for Phase 1
        // Phase 3 will add: FLAG_SUPPORTS_DELETE, FLAG_SUPPORTS_RENAME, etc.

        if (lookup.fileType == FileType.DIRECTORY) {
            // Future: FLAG_DIR_SUPPORTS_CREATE when we support write operations
        }

        return flags
    }

    companion object {
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
```

### File Opening (Read-Only)

```kotlin
class DocumentReader @Inject constructor(
    private val context: Context,
    private val gatewaySwitch: GatewaySwitch
) {
    suspend fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        log(TAG) { "openDocument($documentId, mode=$mode)" }

        val path = DocumentIdCodec.decode(documentId)

        // Phase 1: Read-only support
        if (mode != "r") {
            throw UnsupportedOperationException("Write operations not yet supported")
        }

        // Check cancellation before expensive operation
        signal?.throwIfCanceled()

        return when (path) {
            is LocalPath -> openLocalPath(path, mode)
            is SAFPath -> openSAFPath(path, mode)
            else -> throw IllegalArgumentException("Unsupported path type: ${path::class}")
        }
    }

    private suspend fun openLocalPath(path: LocalPath, mode: String): ParcelFileDescriptor {
        val file = path.file

        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${path.path}")
        }

        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    private suspend fun openSAFPath(path: SAFPath, mode: String): ParcelFileDescriptor {
        // Use SAFGateway to open file
        // May need to use ContentResolver.openFileDescriptor()
        TODO("Implement SAF path opening")
    }
}
```

### Settings (DataStore)

```kotlin
@Serializable
data class ProviderPreferences(
    val showInternalStorage: Boolean = true,
    val showExternalStorage: Boolean = true,
    val showRootAccess: Boolean = false,
    val showADBAccess: Boolean = false,

    // Future: path blacklist
    val blacklistedPaths: Set<String> = emptySet()
)

@Singleton
class DocumentsProviderSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope
) : Settings() {
    override val dataStore = DataStoreValue(
        scope = scope,
        path = "settings_documents_provider",
        defaultValue = ProviderPreferences()
    )

    val showInternalStorage = dataStore.property(
        key = "showInternalStorage",
        reader = { it.showInternalStorage },
        writer = { copy(showInternalStorage = it) }
    )

    val showExternalStorage = dataStore.property(
        key = "showExternalStorage",
        reader = { it.showExternalStorage },
        writer = { copy(showExternalStorage = it) }
    )

    val showRootAccess = dataStore.property(
        key = "showRootAccess",
        reader = { it.showRootAccess },
        writer = { copy(showRootAccess = it) }
    )

    val showADBAccess = dataStore.property(
        key = "showADBAccess",
        reader = { it.showADBAccess },
        writer = { copy(showADBAccess = it) }
    )
}
```

### Main Provider Implementation

```kotlin
class ButlerDocumentsProvider : DocumentsProvider() {

    // Hilt requires manual injection for ContentProvider
    @Inject lateinit var rootQueryHandler: RootQueryHandler
    @Inject lateinit var documentQueryHandler: DocumentQueryHandler
    @Inject lateinit var documentReader: DocumentReader

    override fun onCreate(): Boolean {
        val context = context ?: return false

        // Manual Hilt injection
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DocumentsProviderEntryPoint::class.java
        ).inject(this)

        log(TAG, INFO) { "ButlerDocumentsProvider initialized" }
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        return runBlocking {
            try {
                rootQueryHandler.queryRoots(projection)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryRoots failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())  // Empty cursor on error
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        return runBlocking {
            try {
                documentQueryHandler.queryDocument(documentId, projection)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryDocument failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        return runBlocking {
            try {
                documentQueryHandler.queryChildDocuments(parentDocumentId, projection, sortOrder)
            } catch (e: Exception) {
                log(TAG, ERROR) { "queryChildDocuments failed: ${e.asLog()}" }
                MatrixCursor(projection ?: arrayOf())
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        return runBlocking {
            try {
                documentReader.openDocument(documentId, mode, signal)
            } catch (e: Exception) {
                log(TAG, ERROR) { "openDocument failed: ${e.asLog()}" }
                throw e  // Re-throw for proper error handling
            }
        }
    }

    // Phase 3: Write operations
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        throw UnsupportedOperationException("Write operations not yet supported")
    }

    override fun deleteDocument(documentId: String) {
        throw UnsupportedOperationException("Write operations not yet supported")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        throw UnsupportedOperationException("Write operations not yet supported")
    }

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.documents"
        private val TAG = logTag("Documents", "Provider")
    }
}

@InstallIn(SingletonComponent::class)
@EntryPoint
interface DocumentsProviderEntryPoint {
    fun inject(provider: ButlerDocumentsProvider)
}
```

### AndroidManifest Integration

The provider must be registered in the main app's `AndroidManifest.xml` (not the module's manifest):

**Location**: `app/src/main/AndroidManifest.xml`

```xml
<provider
    android:name="eu.darken.butler.provider.documents.ButlerDocumentsProvider"
    android:authorities="${applicationId}.documents"
    android:exported="true"
    android:grantUriPermissions="true"
    android:permission="android.permission.MANAGE_DOCUMENTS">

    <intent-filter>
        <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
    </intent-filter>
</provider>
```

**Key Attributes**:
- `android:exported="true"` - Allow system access
- `android:grantUriPermissions="true"` - Enable temporary URI permissions
- `android:permission="android.permission.MANAGE_DOCUMENTS"` - Restrict to system only
- Authority uses `${applicationId}` for flavor independence

---

## Implementation Phases

### Phase 1: Basic Read-Only (Internal Storage)

**Goal**: Minimal viable provider - expose internal storage read-only

**Scope**:
- ✅ Single root: Internal Storage (`/storage/emulated/0`)
- ✅ Read-only operations: `queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument` (mode "r")
- ✅ Local paths only (no root/ADB)
- ✅ Basic document ID encoding/decoding
- ✅ Hilt DI setup
- ✅ Error handling (empty cursors on failure)

**Deliverables**:
1. `ButlerDocumentsProvider.kt` - Main provider (queryRoots, queryDocument, queryChildDocuments, openDocument)
2. `DocumentIdCodec.kt` - Encode/decode logic
3. `DocumentRoot.kt` - Sealed class (InternalStorage only)
4. `RootManager.kt` - Root management (returns only InternalStorage)
5. `RootQueryHandler.kt` - queryRoots implementation
6. `DocumentQueryHandler.kt` - queryDocument/queryChildDocuments implementation
7. `DocumentReader.kt` - openDocument implementation
8. `DocumentsProviderModule.kt` - Hilt module
9. `DocumentsProviderSettings.kt` - DataStore settings
10. AndroidManifest entry in main app
11. String resources
12. Drawable resources (icons)

**Testing**:

*Unit Tests (TDD - Write First):*
- **DocumentIdCodec**: 30+ tests covering encoding, decoding, round-trip, stability, edge cases (>95% coverage target)
  - Test strategy: Write ALL tests FIRST, then implement (RED → GREEN → REFACTOR)
  - Critical: Document ID stability is a breaking bug - exhaustive testing required
- **DocumentRoot**: Validation tests for InternalStorage configuration, flags, resource IDs (>90% coverage)
- **RootManager**: Mock-based tests for root visibility logic, settings integration (>85% coverage)

*Unit Tests (Test-After):*
- **Query handlers**: Robolectric tests for cursor population and error handling (~80% coverage)
  - Test cursor column mapping from APathLookup
  - Test error handling (empty cursors on failure)
- **DocumentReader**: Integration tests with real file I/O (~70% coverage)

*Integration Tests:*
- **Instrumented**: ContentProvider registration, queryRoots validation
- **Manual**: Chrome file upload, system file picker browsing, multi-app compatibility

*Performance Tests:*
- Query performance with test file structure (`tooling/test-files/`)
- Memory usage profiling with large directories

**TDD Implementation Timeline**:
- **Day 1** (2-3h): DocumentIdCodec TDD - Write 30+ tests → Implement → Refactor
- **Day 2** (3-4h): DocumentRoot + RootManager TDD - Write tests → Implement with mocks
- **Day 3** (4-6h): Settings + Query handlers (test-after) - Implement → Write Robolectric tests
- **Day 4** (4-6h): DocumentReader + Provider (test-after) - Implement → Integration tests
- **Day 5** (3-4h): Full integration testing + manual verification

**Estimated Effort**: 2-3 days (20-25 hours total including comprehensive testing)

---

### Phase 2: Multiple Roots (SD Card, Root, ADB)

**Goal**: Expose all Butler-accessible storage locations

**Scope**:
- ✅ SD card detection and exposure
- ✅ Root access paths (conditional on root availability)
- ✅ ADB/Shizuku paths (conditional on Shizuku availability)
- ✅ Dynamic root visibility based on permissions
- ✅ Settings to show/hide each root type
- ✅ Settings UI (optional)

**Deliverables**:
1. Update `DocumentRoot.kt` - Add ExternalStorage, RootAccess, ADBAccess
2. Update `RootManager.kt` - Implement SD card detection, permission checking
3. Update `DocumentIdCodec.kt` - Support root/ADB path types
4. Update `DocumentReader.kt` - Support root/ADB path opening
5. `RootVisibilityMonitor.kt` - Monitor permission changes
6. Settings UI fragment (optional)
7. Additional string resources
8. Integration with `app-common-root` and `app-common-adb`

**Dependencies**:
- Understanding of Butler's root access implementation
- Understanding of Butler's ADB/Shizuku integration
- May need to create `RootPath`/`ADBPath` classes if they don't exist

**Testing**:
- Manual: Verify SD card appears when available
- Manual: Verify root access paths when rooted
- Manual: Verify ADB paths when Shizuku connected
- Manual: Verify roots hide/show based on settings

**Estimated Effort**: 3-4 days

---

### Phase 3: Write Operations

**Goal**: Enable file creation, deletion, renaming through provider

**Scope**:
- ✅ `createDocument()` - Create files/directories
- ✅ `deleteDocument()` - Delete files/directories
- ✅ `renameDocument()` - Rename files/directories
- ✅ `copyDocument()` / `moveDocument()` - Copy/move within provider (optional)
- ✅ Proper permission revocation on delete
- ✅ Update document flags to indicate write support

**Deliverables**:
1. `DocumentCreator.kt` - createDocument implementation
2. `DocumentModifier.kt` - deleteDocument, renameDocument implementation
3. `DocumentMover.kt` - copyDocument, moveDocument implementation (optional)
4. Update `DocumentQueryHandler.kt` - Add write flags to documents
5. Update `ButlerDocumentsProvider.kt` - Implement write methods
6. Comprehensive error handling for write conflicts

**Complexity**:
- Permissions: Must properly revoke URI permissions on delete
- Conflicts: Handle file already exists scenarios
- Atomicity: Ensure operations are atomic where possible
- Recursive delete: Delete children and revoke their permissions

**Testing**:
- Manual: Create file through picker
- Manual: Delete file through picker
- Manual: Rename file through picker
- Manual: Verify permission revocation

**Estimated Effort**: 2-3 days

---

### Phase 4: Advanced Features (Future)

**Optional enhancements for later**:

**Search Support**:
- Implement `querySearchDocuments()`
- Add `FLAG_SUPPORTS_SEARCH` to roots
- Full-text search via Butler's existing search infrastructure

**Recents Support**:
- Implement `queryRecentDocuments()`
- Add `FLAG_SUPPORTS_RECENTS` to roots
- Track recently accessed files

**Thumbnail Support**:
- Implement `openDocumentThumbnail()`
- Add `FLAG_SUPPORTS_THUMBNAIL` to image/video files
- Generate thumbnails for media files

**Tree URI Support**:
- Implement `isChildDocument()` properly
- Enable `ACTION_OPEN_DOCUMENT_TREE` for folder selection
- Critical for apps that want persistent folder access

**Settings UI**:
- Full settings screen in Butler
- Configure visible roots
- Blacklist specific paths
- Performance tuning options

---

## Technical Considerations

### Thread Safety

**Challenge**: DocumentsProvider methods run in Binder thread pool (multiple concurrent threads).

**Butler's Gateways**: Already designed for concurrent access (coroutines, Flows, thread-safe operations).

**Strategy**:
- No shared mutable state in provider methods
- All operations delegate to thread-safe gateways
- Use `runBlocking {}` to bridge suspend functions (provider methods are not suspend)
- Hilt @Singleton components are thread-safe by design

**Potential Issue**: `runBlocking` blocks Binder thread. For long operations, consider:
- Setting timeout on operations
- Using cancellation signal properly
- Returning EXTRA_LOADING cursor for slow operations (future optimization)

### Performance Optimization

**Goals**:
- Keep metadata queries < 100ms for good UX
- Avoid main thread blocking in calling app
- Minimize memory usage for large directories

**Strategies**:

1. **Cursor Windowing** (Automatic):
   - MatrixCursor uses CursorWindow (2MB buffer)
   - System handles pagination automatically
   - We just populate cursor row-by-row

2. **Lazy Loading** (Future):
   - Return partial results with `EXTRA_LOADING = true`
   - Fetch remaining data asynchronously
   - Call `notifyChange()` when complete
   - Clients automatically refresh

3. **Caching** (Future):
   - Cache directory listings for 5-10 seconds
   - Invalidate on file operations
   - Use `setNotificationUri()` for automatic refresh

4. **Gateway Optimizations**:
   - Leverage Butler's existing optimized lookups
   - Batch operations where possible
   - Use efficient path walking for large directories

### Error Handling

**Philosophy**: Never crash calling app. Always return empty cursor or throw specific exceptions.

**Error Categories**:

1. **Not Found** (file doesn't exist):
   - `queryDocument()` → return empty cursor
   - `queryChildDocuments()` → return empty cursor
   - `openDocument()` → throw `FileNotFoundException`

2. **Permission Denied** (no access):
   - `queryDocument()` → return empty cursor (silent failure)
   - `openDocument()` → throw `SecurityException` or `FileNotFoundException`

3. **Unsupported Operation**:
   - Write methods in Phase 1/2 → throw `UnsupportedOperationException`

4. **Gateway Errors** (root unavailable, etc.):
   - Log error with context
   - Return empty result
   - Root should hide itself if consistently failing

**Logging Strategy**:
```kotlin
try {
    // Operation
} catch (e: Exception) {
    log(TAG, ERROR) { "Operation failed for $documentId: ${e.asLog()}" }
    // Return safe default or re-throw
}
```

### Security & Privacy

**URI Permission Model**:
- System grants temporary permissions to calling apps
- Permissions tied to specific content URIs
- Automatically revoked when activity finishes (unless persisted)

**Our Responsibilities**:
1. **Access Control**: Only expose roots that user has enabled in settings
2. **Path Blacklisting**: Don't expose sensitive paths (future: `/data/data`, private app dirs)
3. **Permission Revocation**: Call `revokeDocumentPermission()` after delete
4. **Root Access Safety**: Carefully control what root paths are exposed

**Manifest Security**:
- `android:permission="android.permission.MANAGE_DOCUMENTS"` restricts provider to system only
- Prevents arbitrary apps from querying provider directly
- Only system document picker can access

**Potential Concerns**:
- Exposing root filesystem could allow apps to access sensitive data
- **Mitigation**: Make root access opt-in, show clear warnings
- Consider blacklisting critical system paths

### Dependency Injection (Hilt)

**Challenge**: ContentProvider lifecycle managed by system, not Hilt.

**Solution**: Manual injection via EntryPoint:

```kotlin
@InstallIn(SingletonComponent::class)
@EntryPoint
interface DocumentsProviderEntryPoint {
    fun inject(provider: ButlerDocumentsProvider)

    // Alternative: expose components directly
    fun rootQueryHandler(): RootQueryHandler
    fun documentQueryHandler(): DocumentQueryHandler
    fun documentReader(): DocumentReader
}

class ButlerDocumentsProvider : DocumentsProvider() {
    @Inject lateinit var rootQueryHandler: RootQueryHandler
    // ... other injections

    override fun onCreate(): Boolean {
        val context = context ?: return false

        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DocumentsProviderEntryPoint::class.java
        ).inject(this)

        return true
    }
}
```

**Module Definition**:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DocumentsProviderModule {
    // No explicit bindings needed if using @Inject constructors
    // Hilt auto-generates providers for @Inject constructor classes
}
```

**Testing**: Can inject test implementations via custom EntryPoint for unit tests.

### Compatibility Considerations

**Minimum API Level**: 19 (KitKat 4.4) - DocumentsProvider introduction

**Version-Specific Features**:
- API 21: `removeDocument()`, `isChildDocument()`
- API 24: `copyDocument()`, `moveDocument()`, virtual documents
- API 26: `findDocumentPath()`

**Strategy**:
- Target minimum API 19 for maximum compatibility
- Use `@RequiresApi` annotations for version-specific features
- Phase 1-3 only use API 19 methods (future phases can use advanced features)

**Flavor Compatibility**:
- FOSS flavor: Full support (no Google Play dependencies)
- GPLAY flavor: Full support
- Authority uses `${applicationId}` for flavor independence

---

## Testing Strategy

### Overview: TDD Approach

This implementation follows a **selective TDD strategy**: Write tests first for pure logic and business rules (DocumentIdCodec, RootManager), and test-after for Android framework integration (query handlers, ContentProvider).

**Why TDD for DocumentsProvider?**
- **Document ID stability** is a critical correctness requirement - bugs here break client apps
- **Pure business logic** (encoding, root management) benefits from TDD's fast feedback
- **Android framework code** (cursors, ContentProvider) is better tested after implementation

### Component-by-Component Testing Analysis

---

#### 1. DocumentIdCodec - **TDD Priority: CRITICAL** ⭐⭐⭐

**Why Perfect for TDD:**
- Pure functions with no side effects or Android dependencies
- Document ID stability is a **breaking bug** if incorrect - must be tested exhaustively
- Fast test execution (milliseconds)
- Clear specification in PLAN.md

**Test Strategy: Write ALL tests FIRST, then implement**

**Complete Test Suite (30+ tests)**:

```kotlin
class DocumentIdCodecTest {

    @Nested
    inner class Encoding {
        @Test
        fun `encode LocalPath with simple absolute path`() {
            val path = LocalPath.build("/storage/emulated/0/Download/file.pdf")
            val encoded = DocumentIdCodec.encode(path)

            assertTrue(encoded.startsWith("local|"))
            assertFalse(encoded.contains("/")) // Base64 shouldn't contain slashes
        }

        @Test
        fun `encode path with special characters`() {
            val path = LocalPath.build("/storage/test file (1) [copy].txt")
            val encoded = DocumentIdCodec.encode(path)

            assertNotNull(encoded)
            assertTrue(encoded.split("|").size == 2)
        }

        @Test
        fun `encode path with Unicode characters`() {
            val path = LocalPath.build("/storage/emulated/0/文件/ファイル.txt")
            val encoded = DocumentIdCodec.encode(path)

            assertNotNull(encoded)
            assertTrue(encoded.contains("|"))
        }

        @Test
        fun `encode very long path - no length limit`() {
            val longPath = "/storage/emulated/0/" + "a".repeat(500) + "/file.pdf"
            val path = LocalPath.build(longPath)
            val encoded = DocumentIdCodec.encode(path)

            assertNotNull(encoded)
        }

        @Test
        fun `encode path with pipe characters in filename`() {
            val path = LocalPath.build("/storage/file|with|pipes.txt")
            val encoded = DocumentIdCodec.encode(path)

            // Base64 part should not expose pipe characters
            val parts = encoded.split("|")
            assertEquals(2, parts.size)
        }
    }

    @Nested
    inner class Decoding {
        @Test
        fun `decode valid document ID returns correct path`() {
            val documentId = "local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9maWxlLnBkZg"
            val path = DocumentIdCodec.decode(documentId)

            assertEquals(LocalPath.build("/storage/emulated/0/file.pdf"), path)
        }

        @Test
        fun `decode throws on malformed document ID - missing parts`() {
            assertThrows<IllegalArgumentException> {
                DocumentIdCodec.decode("local") // Missing encoded path
            }
        }

        @Test
        fun `decode throws on malformed document ID - too many parts`() {
            assertThrows<IllegalArgumentException> {
                DocumentIdCodec.decode("local|base64|extra")
            }
        }

        @Test
        fun `decode throws on invalid base64`() {
            assertThrows<IllegalArgumentException> {
                DocumentIdCodec.decode("local|NOT_VALID_BASE64!!!")
            }
        }

        @Test
        fun `decode throws on unknown path type`() {
            assertThrows<IllegalArgumentException> {
                DocumentIdCodec.decode("unknown_type|L3N0b3JhZ2U")
            }
        }

        @Test
        fun `decode empty document ID throws`() {
            assertThrows<IllegalArgumentException> {
                DocumentIdCodec.decode("")
            }
        }
    }

    @Nested
    inner class RoundTrip {
        @ParameterizedTest
        @ValueSource(strings = [
            "/storage/emulated/0/Download/file.pdf",
            "/storage/test file (1).txt",
            "/storage/emulated/0/文件.txt",
            "/storage/emulated/0/a/b/c/d/e/f/g/deep.txt",
            "/storage/My Documents/Report [Final] (2).docx",
            "/storage/file with\ttab.txt",
            "/storage/file with\nnewline.txt"
        ])
        fun `encode and decode round trip preserves path`(pathString: String) {
            val original = LocalPath.build(pathString)
            val encoded = DocumentIdCodec.encode(original)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(original, decoded)
        }

        @Test
        fun `round trip with root path`() {
            val path = LocalPath.build("/")
            val encoded = DocumentIdCodec.encode(path)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(path, decoded)
        }

        @Test
        fun `round trip with different storage locations`() {
            val paths = listOf(
                LocalPath.build("/storage/emulated/0/file.txt"),  // Internal storage
                LocalPath.build("/storage/1234-5678/file.txt"),   // SD card
                LocalPath.build("/system/build.prop"),            // System path
                LocalPath.build("/")                               // Root
            )

            paths.forEach { original ->
                val encoded = DocumentIdCodec.encode(original)
                val decoded = DocumentIdCodec.decode(encoded)
                assertEquals(original, decoded)
            }
        }
    }

    @Nested
    inner class Stability {
        @Test
        fun `same input produces same output - stability guarantee`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")

            val encoded1 = DocumentIdCodec.encode(path)
            val encoded2 = DocumentIdCodec.encode(path)
            val encoded3 = DocumentIdCodec.encode(path)

            assertEquals(encoded1, encoded2)
            assertEquals(encoded2, encoded3)
        }

        @Test
        fun `document ID format matches specification`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")
            val encoded = DocumentIdCodec.encode(path)

            val parts = encoded.split("|")
            assertEquals(2, parts.size, "Format: pathType|base64")
            assertEquals("local", parts[0])
            assertTrue(parts[1].isNotEmpty(), "Base64 part should not be empty")
        }

        @Test
        fun `document ID does not contain path separators in base64`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")
            val encoded = DocumentIdCodec.encode(path)

            val parts = encoded.split("|")
            val base64Part = parts[1]

            // URL-safe Base64 should not contain / or +
            assertFalse(base64Part.contains("/"))
            assertFalse(base64Part.contains("+"))
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `encode path with consecutive slashes - normalized by LocalPath`() {
            // LocalPath may normalize - test the codec handles whatever LocalPath returns
            val path = LocalPath.build("/storage//emulated///0/file.pdf")
            val encoded = DocumentIdCodec.encode(path)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(path, decoded)
        }

        @Test
        fun `encode path with trailing slash`() {
            val path1 = LocalPath.build("/storage/emulated/0/folder")
            val path2 = LocalPath.build("/storage/emulated/0/folder/")

            // Document behavior: LocalPath normalization
            val encoded1 = DocumentIdCodec.encode(path1)
            val encoded2 = DocumentIdCodec.encode(path2)

            assertNotNull(encoded1)
            assertNotNull(encoded2)
        }

        @Test
        fun `encode path with only root`() {
            val path = LocalPath.build("/")
            val encoded = DocumentIdCodec.encode(path)

            assertNotNull(encoded)
            assertTrue(encoded.startsWith("local|"))
        }
    }

    @Nested
    inner class SAFPathEncoding {
        @Test
        fun `encode and decode SAFPath round trip`() {
            val original = SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                segments = listOf("subfolder", "file.txt")
            )
            val encoded = DocumentIdCodec.encode(original)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(original, decoded)
            assertTrue(decoded is SAFPath)
            assertEquals(original.treeRoot, (decoded as SAFPath).treeRoot)
            assertEquals(original.segments, decoded.segments)
        }

        @Test
        fun `encode SAFPath with empty segments`() {
            val safPath = SAFPath(
                treeRoot = "content://authority/tree/root",
                segments = emptyList()
            )
            val encoded = DocumentIdCodec.encode(safPath)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(safPath, decoded)
            assertTrue((decoded as SAFPath).segments.isEmpty())
        }

        @Test
        fun `encode SAFPath with special characters in segments`() {
            val safPath = SAFPath(
                treeRoot = "content://authority/tree/root",
                segments = listOf("folder with spaces", "file (1).txt", "文件.pdf")
            )
            val encoded = DocumentIdCodec.encode(safPath)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(safPath, decoded)
        }

        @Test
        fun `encode SAFPath with deep nesting`() {
            val safPath = SAFPath(
                treeRoot = "content://authority/tree/root",
                segments = listOf("a", "b", "c", "d", "e", "f", "g", "file.txt")
            )
            val encoded = DocumentIdCodec.encode(safPath)
            val decoded = DocumentIdCodec.decode(encoded)

            assertEquals(safPath, decoded)
        }

        @Test
        fun `SAFPath document ID starts with saf pathType`() {
            val safPath = SAFPath(
                treeRoot = "content://authority/tree/root",
                segments = listOf("file.txt")
            )
            val encoded = DocumentIdCodec.encode(safPath)

            assertTrue(encoded.startsWith("saf|"))
        }

        @Test
        fun `SAFPath document ID is JSON-based`() {
            val safPath = SAFPath(
                treeRoot = "content://authority/tree/root",
                segments = listOf("file.txt")
            )
            val encoded = DocumentIdCodec.encode(safPath)
            val parts = encoded.split("|")

            assertEquals(2, parts.size)
            assertEquals("saf", parts[0])

            // Decode base64 and verify it's JSON
            val jsonBytes = Base64.decode(parts[1], Base64.URL_SAFE)
            val jsonString = String(jsonBytes)
            assertTrue(jsonString.contains("treeRoot"))
            assertTrue(jsonString.contains("segments"))
        }
    }
}
```

**Coverage Goal**: >95% - This is the **stability foundation** of the entire provider

**TDD Workflow**:
1. ✅ Write all 30+ tests FIRST (they will fail - RED)
2. ✅ Implement `DocumentIdCodec.encode()` - watch tests turn GREEN
3. ✅ Implement `DocumentIdCodec.decode()` - watch tests turn GREEN
4. ✅ Refactor with confidence - tests prevent regressions

---

#### 2. DocumentRoot Sealed Class - **TDD Priority: HIGH** ⭐⭐⭐

**Why Excellent for TDD:**
- Simple data structure validation
- No external dependencies
- Clear requirements

**Test Suite**:

```kotlin
class DocumentRootTest {

    @Test
    fun `InternalStorage has correct configuration`() {
        val root = DocumentRoot.InternalStorage

        assertEquals("internal", root.id)
        assertTrue(root.path is LocalPath)
        assertEquals("/storage/emulated/0", root.path.path)
        assertNotEquals(0, root.icon)
        assertNotEquals(0, root.titleRes)
    }

    @Test
    fun `InternalStorage has required DocumentsProvider flags`() {
        val flags = DocumentRoot.InternalStorage.flags

        assertTrue(flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD != 0)
        assertTrue(flags and DocumentsContract.Root.FLAG_LOCAL_ONLY != 0)
    }

    @Test
    fun `all root IDs are unique`() {
        val allRoots = listOf(
            DocumentRoot.InternalStorage.id,
            // Future: ExternalStorage instances will need dynamic ID checks
        )

        assertEquals(allRoots.size, allRoots.distinct().size)
    }

    @Test
    fun `all root icons are valid resource IDs`() {
        assertTrue(DocumentRoot.InternalStorage.icon > 0)
    }

    @Test
    fun `all root title resources are valid`() {
        assertTrue(DocumentRoot.InternalStorage.titleRes > 0)
    }
}
```

**Coverage Goal**: >90%

---

#### 3. RootManager - **TDD Priority: HIGH** ⭐⭐

**Why Good for TDD:**
- Business logic with clear inputs/outputs
- Dependencies can be mocked
- Deterministic behavior

**Test Suite with Mocks**:

```kotlin
class RootManagerTest {

    private lateinit var mockSettings: DocumentsProviderSettings
    private lateinit var mockGatewaySwitch: GatewaySwitch
    private lateinit var mockContext: Context
    private lateinit var rootManager: RootManager

    @BeforeEach
    fun setup() {
        mockSettings = mock()
        mockGatewaySwitch = mock()
        mockContext = mock()

        // Setup default mock returns
        whenever(mockSettings.showInternalStorage.value()).thenReturn(true)
        whenever(mockSettings.showExternalStorage.value()).thenReturn(false)
        whenever(mockSettings.showRootAccess.value()).thenReturn(false)
        whenever(mockSettings.showADBAccess.value()).thenReturn(false)

        rootManager = RootManager(
            context = mockContext,
            gatewaySwitch = mockGatewaySwitch,
            settings = mockSettings
        )
    }

    @Test
    fun `getAvailableRoots returns InternalStorage when enabled`() = runTest {
        // Given: Internal storage enabled (already set in setup)

        // When
        val roots = rootManager.getAvailableRoots()

        // Then
        assertEquals(1, roots.size)
        assertTrue(roots[0] is DocumentRoot.InternalStorage)
    }

    @Test
    fun `getAvailableRoots returns empty list when all disabled`() = runTest {
        // Given
        whenever(mockSettings.showInternalStorage.value()).thenReturn(false)

        // When
        val roots = rootManager.getAvailableRoots()

        // Then
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `getAvailableRoots respects all setting toggles`() = runTest {
        // Given: All enabled
        whenever(mockSettings.showInternalStorage.value()).thenReturn(true)
        whenever(mockSettings.showExternalStorage.value()).thenReturn(true)
        whenever(mockSettings.showRootAccess.value()).thenReturn(true)
        whenever(mockSettings.showADBAccess.value()).thenReturn(true)

        // When
        val roots = rootManager.getAvailableRoots()

        // Then: Phase 1 only has internal, but test structure ready
        assertTrue(roots.isNotEmpty())
        verify(mockSettings).showInternalStorage
        verify(mockSettings).showExternalStorage
    }

    @Test
    fun `getRootById returns correct root`() {
        // When
        val root = rootManager.getRootById("internal")

        // Then
        assertNotNull(root)
        assertEquals(DocumentRoot.InternalStorage, root)
    }

    @Test
    fun `getRootById returns null for unknown ID`() {
        // When
        val root = rootManager.getRootById("unknown_root_id")

        // Then
        assertNull(root)
    }

    @Test
    fun `getRootById returns null for disabled root`() = runTest {
        // Given: Internal storage disabled
        whenever(mockSettings.showInternalStorage.value()).thenReturn(false)

        // When
        val root = rootManager.getRootById("internal")

        // Then: Should not return disabled roots
        assertNull(root)
    }
}
```

**Coverage Goal**: >85%

**Mock Setup Note**: `DocumentsProviderSettings.showInternalStorage` returns a `DataStoreValue` wrapper. You'll need to mock the `.value()` extension function. Consider creating a test fake:

```kotlin
class FakeDataStoreValue<T>(private var testValue: T) : DataStoreValue<T> {
    suspend fun value(): T = testValue
    fun value(newValue: T) { testValue = newValue }
}
```

---

#### 4. Query Handlers - **TDD Priority: MODERATE** ⭐

**Why Moderate TDD Viability:**
- Requires Android framework classes (`MatrixCursor`, `DocumentsContract`)
- Need Robolectric for unit testing OR test-after with integration tests
- Setup overhead is high

**Recommendation**: **Test-after** with Robolectric OR integration tests for faster iteration

**Test Strategy (with Robolectric)**:

```kotlin
@RunWith(RobolectricTestRunner::class)
class DocumentQueryHandlerTest {

    private lateinit var mockGatewaySwitch: GatewaySwitch
    private lateinit var mockRootManager: RootManager
    private lateinit var handler: DocumentQueryHandler

    @Before
    fun setup() {
        mockGatewaySwitch = mock()
        mockRootManager = mock()
        handler = DocumentQueryHandler(mockGatewaySwitch, mockRootManager)
    }

    @Test
    fun `queryDocument returns cursor with file metadata`() = runTest {
        // Given
        val documentId = "internal|local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9maWxlLnBkZg"
        val mockLookup = createMockLookup(
            name = "file.pdf",
            size = 1024L,
            mimeType = "application/pdf"
        )
        whenever(mockGatewaySwitch.lookup(any(), any())).thenReturn(mockLookup)

        // When
        val cursor = handler.queryDocument(documentId, null)

        // Then
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("file.pdf", cursor.getString(
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        ))
        assertEquals(1024L, cursor.getLong(
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        ))
    }

    @Test
    fun `queryDocument returns empty cursor on gateway error`() = runTest {
        // Given
        val documentId = "internal|local|L3N0b3JhZ2U"
        whenever(mockGatewaySwitch.lookup(any(), any()))
            .thenThrow(RuntimeException("File not found"))

        // When
        val cursor = handler.queryDocument(documentId, null)

        // Then
        assertEquals(0, cursor.count, "Should return empty cursor on error")
    }

    @Test
    fun `queryChildDocuments returns multiple children`() = runTest {
        // Given
        val parentDocId = "internal|local|L3N0b3JhZ2UvZW11bGF0ZWQvMA"
        val mockChildren = listOf(
            createMockLookup("file1.txt", 100L, "text/plain"),
            createMockLookup("file2.txt", 200L, "text/plain")
        )
        whenever(mockGatewaySwitch.lookupFiles(any(), any())).thenReturn(mockChildren)

        // When
        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        // Then
        assertEquals(2, cursor.count)
    }

    private fun createMockLookup(
        name: String,
        size: Long,
        mimeType: String
    ): APathLookup<LocalPath> = mock {
        on { this.name } doReturn name
        on { this.size } doReturn size
        on { this.mimeType } doReturn mimeType
        on { fileType } doReturn FileType.FILE
        on { path } doReturn LocalPath.build("/storage/emulated/0/$name")
    }
}
```

**Coverage Goal**: >80%

---

#### 5. DocumentReader - **TDD Priority: LOW (Integration Test)**

**Why Integration Test is Better:**
- `ParcelFileDescriptor` requires real file I/O
- Android framework lifecycle
- Test-after with real files is faster

**Test Strategy**:

```kotlin
@RunWith(AndroidJUnit4::class)
class DocumentReaderIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var documentReader: DocumentReader

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gatewaySwitch = mock<GatewaySwitch>() // Or use real gateway
        documentReader = DocumentReader(context, gatewaySwitch)
    }

    @Test
    fun `openDocument returns readable ParcelFileDescriptor`() = runTest {
        // Given: Create test file
        val testFile = tempFolder.newFile("test.txt")
        testFile.writeText("Hello DocumentsProvider")

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = DocumentIdCodec.encode("internal", path)

        // When
        val pfd = documentReader.openDocument(documentId, "r", null)

        // Then: Can read file contents
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("Hello DocumentsProvider", content)
        }

        pfd.close()
    }

    @Test
    fun `openDocument throws on write mode in Phase 1`() = runTest {
        val documentId = "internal|local|L3N0b3JhZ2Uv"

        assertThrows<UnsupportedOperationException> {
            documentReader.openDocument(documentId, "w", null)
        }
    }
}
```

**Coverage Goal**: >70%

---

#### 6. ButlerDocumentsProvider - **Integration Test Only**

**Why Unit Testing is Impractical:**
- ContentProvider lifecycle managed by Android system
- Requires ContentResolver
- Better tested end-to-end

**Test Strategy**:

```kotlin
@RunWith(AndroidJUnit4::class)
class ButlerDocumentsProviderIntegrationTest {

    @Test
    fun `provider is registered and accessible`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.documents"

        val providerInfo = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(providerInfo, "Provider should be registered in manifest")
    }

    @Test
    fun `queryRoots returns at least one root`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.documents"
        val uri = DocumentsContract.buildRootsUri(authority)

        val cursor = context.contentResolver.query(uri, null, null, null, null)

        assertNotNull(cursor)
        assertTrue(cursor!!.count > 0, "Should return at least Internal Storage root")
        cursor.close()
    }
}
```

**Coverage Goal**: >60% (mostly via manual testing)

---

### Test Coverage Goals Summary

| Component | Coverage Target | TDD Viability | Testing Approach | Priority |
|-----------|----------------|---------------|------------------|----------|
| **DocumentIdCodec** | >95% | ⭐⭐⭐ Critical | TDD - Write tests first | **CRITICAL** |
| **DocumentRoot** | >90% | ⭐⭐⭐ High | TDD - Write tests first | High |
| **RootManager** | >85% | ⭐⭐ Good | TDD with mocks | High |
| **DocumentsProviderSettings** | >70% | ⭐ Low | Test-after (DataStore tested by framework) | Medium |
| **DocumentQueryHandler** | >80% | ⭐ Moderate | Test-after with Robolectric OR integration | Medium |
| **RootQueryHandler** | >80% | ⭐ Moderate | Test-after with Robolectric OR integration | Medium |
| **DocumentReader** | >70% | ❌ None | Integration tests with real I/O | Medium |
| **ButlerDocumentsProvider** | >60% | ❌ None | Integration + manual testing | Low |

**Overall Project Coverage Target**: >80%

---

### Testing Infrastructure Requirements

**Add to `app-provider-documents/build.gradle.kts`**:

```kotlin
dependencies {
    // Unit testing framework
    testImplementation(project(":app-common-test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")

    // Mocking
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")

    // Coroutines testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Android framework testing (Robolectric for query handlers)
    testImplementation("org.robolectric:robolectric:4.11.1")

    // Integration tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
```

---

### Recommended TDD Implementation Order

**Phase 1: High-Value TDD Components (Write Tests First)**

```
Day 1: DocumentIdCodec (TDD)
├─ Write all 30+ tests (encode, decode, round-trip, stability, edge cases) → RED
├─ Implement encode() → GREEN
├─ Implement decode() → GREEN
└─ Refactor with confidence
   Expected: 2-3 hours coding, >95% coverage

Day 2: DocumentRoot + RootManager (TDD)
├─ Write DocumentRoot validation tests → GREEN (data class)
├─ Write RootManager tests with mocks → RED
├─ Implement RootManager.getAvailableRoots() → GREEN
├─ Implement RootManager.getRootById() → GREEN
└─ Refactor
   Expected: 3-4 hours coding, >85% coverage
```

**Phase 2: Test-After for Android Framework Integration**

```
Day 3: Settings + Query Handlers (Test-After)
├─ Implement DocumentsProviderSettings (DataStore pattern)
├─ Implement RootQueryHandler
├─ Implement DocumentQueryHandler
├─ Write Robolectric tests for query handlers
└─ Write smoke tests for settings
   Expected: 4-6 hours coding, ~80% coverage

Day 4: DocumentReader + Provider (Test-After)
├─ Implement DocumentReader
├─ Implement ButlerDocumentsProvider
├─ Wire up Hilt DI
├─ Write integration tests
└─ AndroidManifest setup
   Expected: 4-6 hours coding, ~70% coverage

Day 5: Integration & Manual Testing
├─ Run all unit tests (should be >80% coverage)
├─ Run integration tests
├─ Manual testing with Chrome, Gmail, Documents UI
└─ Fix issues, iterate
   Expected: 3-4 hours testing
```

**Result**: ~50% of code written with TDD (the critical stability parts), ~50% test-after for faster framework integration.

---

### Integration Testing

**Manual Test Cases**:

1. **Basic Read Operations**:
   - Open Chrome → Upload file → Select "Butler - Internal Storage"
   - Browse directories, verify hierarchy
   - Select file, verify upload succeeds

2. **Multiple Roots** (Phase 2):
   - Insert SD card, verify "Butler - SD Card" appears
   - Enable root access, verify "Butler - Root Access" appears
   - Connect Shizuku, verify "Butler - ADB Access" appears

3. **Write Operations** (Phase 3):
   - Use Documents UI → Create folder via Butler
   - Delete file via Butler
   - Rename file via Butler

4. **App Compatibility**:
   - Gmail: Attach file from Butler
   - Google Drive: Upload file from Butler
   - Messaging apps: Share media from Butler
   - File managers: Browse Butler roots

5. **Error Scenarios**:
   - Try to access non-existent file (should gracefully fail)
   - Try to access without permissions (should return empty)
   - Revoke root access while browsing (should handle cleanly)

### Performance Testing

**Benchmarks**:
1. Time to list 1,000 files in directory: Target < 200ms
2. Time to return root list: Target < 50ms
3. Time to open file for reading: Target < 100ms

**Tools**:
- Android Profiler (CPU, memory usage)
- Systrace for threading analysis
- Custom logging with timestamps

**Test Directories**:
- Use test file structure creator in `tooling/test-files/`
- Test with `adirwithmanyfiles/` (4,000 small files)
- Test with `adirwithnesteddata/` (deep hierarchy)

---

## Risks & Mitigations

### Risk 1: Document ID Stability Violations

**Risk**: Accidentally changing document IDs breaks client apps, causes data loss.

**Impact**: HIGH - Critical requirement of DocumentsProvider API.

**Mitigation**:
- **TDD-First Approach**: 30+ tests written BEFORE DocumentIdCodec implementation (see Testing Strategy section)
  - Test coverage target: >95% for DocumentIdCodec
  - Tests cover: encoding, decoding, round-trip, stability guarantees, edge cases
  - Parameterized tests for special characters, Unicode, long paths
  - Stability tests verify same input produces same output consistently
- **Design guarantees**: Use absolute paths only (never relative), Base64 URL-safe encoding
- **Format specification**: Documented format (`rootId|pathType|base64`) enforced by tests
- **Exception handling**: Document the one exception (rename operations can return new ID)
- **Code review**: Focus on ID generation changes, require test updates for any codec modifications
- **Regression prevention**: Comprehensive test suite prevents accidental breaking changes

### Risk 2: Performance Issues with Large Directories

**Risk**: Listing directories with thousands of files blocks Binder thread, causes ANRs.

**Impact**: MEDIUM - Poor user experience, potential app crashes.

**Mitigation**:
- Test with large directories early (use test file creator)
- Implement cursor windowing (automatic with MatrixCursor)
- Future: EXTRA_LOADING pattern for truly massive directories
- Set reasonable timeouts on gateway operations

### Risk 3: Root Access Security Concerns

**Risk**: Exposing root filesystem allows apps to access sensitive data.

**Impact**: HIGH - Privacy/security issue.

**Mitigation**:
- Make root access opt-in (disabled by default)
- Show clear warning when enabling root access
- Consider path blacklist for critical system directories
- Extensive testing of permission model
- Future: Whitelist-based approach instead of blacklist

### Risk 4: Permission Revocation Failures

**Risk**: Forgetting to revoke permissions after delete leaks access to deleted content.

**Impact**: MEDIUM - Security issue, permissions linger.

**Mitigation**:
- Centralize delete logic with mandatory revocation
- Unit tests verify revocation in all delete paths
- Code review checklist includes permission revocation
- Future: Automated test that verifies permission cleanup

### Risk 5: Thread Safety Issues

**Risk**: Concurrent access to shared state causes race conditions.

**Impact**: MEDIUM - Data corruption, crashes.

**Mitigation**:
- Avoid shared mutable state
- Rely on thread-safe Butler gateways
- Use Hilt @Singleton for thread-safe component lifecycle
- Stress test with multiple concurrent queries

### Risk 6: Gateway Dependency Changes

**Risk**: Changes to Butler's gateway APIs break provider integration.

**Impact**: LOW - Internal to Butler, can be coordinated.

**Mitigation**:
- Minimize direct gateway API usage (use GatewaySwitch)
- Comprehensive integration tests
- Maintain this PLAN.md documenting dependencies
- Version compatibility testing during Butler refactors

---

## Success Criteria

### Phase 1 Success Criteria

✅ **Functional**:
1. Butler appears in system document picker
2. Can browse internal storage directories
3. Can open files for reading in other apps
4. No crashes in provider or calling apps

✅ **Quality**:
1. All public methods have error handling
2. Test coverage meets targets:
   - DocumentIdCodec: >95% (TDD with 30+ tests)
   - RootManager: >85%
   - Overall project: >80%
3. All TDD components (DocumentIdCodec, RootManager) have tests written first
4. No memory leaks detected in profiler
5. Logging provides clear debugging trail

✅ **User Experience**:
1. Browsing feels responsive (< 200ms for typical directories)
2. Provider icon and name clearly identify Butler
3. Works with at least 5 popular apps (Chrome, Gmail, Drive, etc.)

### Phase 2 Success Criteria

✅ **Functional**:
1. SD card detected and exposed when available
2. Root access paths shown when root available (opt-in)
3. ADB access paths shown when Shizuku connected (opt-in)
4. Settings allow show/hide each root type

✅ **Quality**:
1. Dynamic root visibility updates without app restart
2. Graceful degradation when permissions unavailable
3. Clear user feedback for permission requirements

### Phase 3 Success Criteria

✅ **Functional**:
1. Can create files/folders through picker
2. Can delete files through picker
3. Can rename files through picker
4. URI permissions properly revoked on delete

✅ **Quality**:
1. Write operations are atomic (no partial state)
2. Conflicts handled gracefully (file exists, etc.)
3. No orphaned permissions after deletions

---

## Future Enhancements

### Search Integration

Leverage Butler's existing search infrastructure:
- Implement `querySearchDocuments()`
- Integrate with Explorer/Searcher workspace search engine
- Support full-text search across all roots
- Add `FLAG_SUPPORTS_SEARCH` to roots

### Cloud Storage Integration

If Butler adds cloud storage support:
- New root type: `CloudStorage`
- EXTRA_LOADING pattern for network fetches
- Thumbnail caching for cloud images
- Offline availability indicators

### Recents Tracking

Track frequently/recently accessed files:
- Implement `queryRecentDocuments()`
- Persist access history in Room database
- Privacy setting to disable tracking
- Integration with Butler's general recents/history

### Thumbnail Generation

For image/video files:
- Implement `openDocumentThumbnail()`
- Use Coil for thumbnail generation/caching
- Support various size hints
- Efficient cancellation support

### Settings UI

Full-featured settings screen:
- Toggle each root type visibility
- Blacklist specific paths from exposure
- Configure thumbnail generation settings
- View provider statistics (queries served, errors, etc.)

### Path Blacklisting

Prevent exposure of sensitive paths:
- System path blacklist (`/data/data`, `/system/etc/shadow`)
- User-customizable blacklist
- Whitelist mode for root access (safer default)

### ACTION_OPEN_DOCUMENT_TREE Support

Enable folder selection:
- Implement `isChildDocument()` fully
- Test with apps requiring tree access
- Persistent folder permissions

---

## Documentation Requirements

### Code Documentation

1. **KDoc for All Public APIs**:
   - DocumentIdCodec methods
   - DocumentRoot sealed class
   - RootManager public methods
   - Query handlers public methods

2. **Inline Comments**:
   - Document ID format specification
   - Tricky encoding/decoding logic
   - Permission revocation requirements
   - Thread safety considerations

3. **Architecture Decision Records** (ADRs):
   - Why Base64 encoding for document IDs
   - Why delegation to gateways vs reimplementation
   - Phased implementation rationale

### User Documentation

1. **Feature Documentation**:
   - How to enable Butler in file picker
   - How to configure visible roots
   - Privacy implications of root access
   - Troubleshooting guide

2. **Developer Documentation**:
   - How to test provider locally
   - How to add new root types
   - How to debug document ID issues

### Maintenance Documentation

1. **This PLAN.md**:
   - Keep updated as implementation progresses
   - Document deviations from original plan
   - Record lessons learned

2. **Testing Documentation**:
   - Test case catalog
   - Performance benchmarks
   - Compatibility matrix (apps tested)

---

## Open Questions

### Architecture Questions

1. **Q**: Does Butler have `RootPath` and `ADBPath` implementations of `APath`?
   - **Impact**: Affects Phase 2 implementation
   - **Resolution**: Investigate `app-common-root` and `app-common-adb` modules

2. **Q**: How does Butler currently handle root permission acquisition?
   - **Impact**: Need to integrate permission checking in RootManager
   - **Resolution**: Study root gateway implementation

3. **Q**: What's the exact structure of Butler's ADB/Shizuku integration?
   - **Impact**: ADB root visibility and path access
   - **Resolution**: Study ADB gateway and Shizuku integration code

### Implementation Questions

1. **Q**: Should we use `runBlocking` or implement async cursor loading?
   - **Tradeoff**: Simplicity vs performance for large directories
   - **Recommendation**: Start with `runBlocking`, optimize later with EXTRA_LOADING if needed

2. **Q**: How to detect SD card mount/unmount for dynamic root visibility?
   - **Options**: StorageVolumeCallback, BroadcastReceiver, polling
   - **Recommendation**: Use StorageManager.registerListener (API 24+) with fallback

3. **Q**: Should root access paths be blacklist or whitelist based?
   - **Security**: Whitelist safer (opt-in specific paths)
   - **Usability**: Blacklist more flexible (expose everything except dangerous paths)
   - **Recommendation**: Start with blacklist, add whitelist mode in settings

### User Experience Questions

1. **Q**: How to communicate permission requirements to users?
   - **Context**: Root/ADB roots hidden when permissions unavailable
   - **Options**: Summary text in root, dedicated settings explanation, in-app tutorial
   - **Recommendation**: Summary text + settings explanation

2. **Q**: Should write operations require confirmation?
   - **Context**: Deleting via provider might be unexpected
   - **Tradeoff**: Safety vs friction
   - **Recommendation**: No confirmation (system picker already confirms), rely on recycle bin (future)

---

## Appendix

### Key Android Documentation

- [DocumentsProvider Reference](https://developer.android.com/reference/android/provider/DocumentsProvider)
- [Storage Access Framework Guide](https://developer.android.com/guide/topics/providers/document-provider)
- [DocumentsContract Reference](https://developer.android.com/reference/android/provider/DocumentsContract)

### Butler Architecture References

- `app-common-io/APath.kt` - Abstract path system
- `app-common-io/GatewaySwitch.kt` - Gateway routing
- `app-common-io/FileSystemOps.kt` - File operations interface
- `app-common-io/APathLookup.kt` - File metadata

### Code Estimates

**Lines of Code Estimates**:
- Phase 1: ~600-800 lines
- Phase 2: +300-400 lines
- Phase 3: +400-500 lines
- Total: ~1,300-1,700 lines

**Files Created**:
- Phase 1: ~12 Kotlin files + resources
- Phase 2: +3-4 Kotlin files
- Phase 3: +3-4 Kotlin files
- Total: ~18-20 Kotlin files

### Development Timeline Estimate

**Phase 1** (Basic Read-Only):
- Setup & architecture: 0.5 day
- Document ID codec: 0.5 day
- Root management: 0.5 day
- Query handlers: 1 day
- Document reader: 0.5 day
- Integration & testing: 0.5 day
- **Total: 2-3 days**

**Phase 2** (Multiple Roots):
- SD card detection: 0.5 day
- Root access integration: 1 day
- ADB access integration: 1 day
- Dynamic visibility: 0.5 day
- Testing: 0.5 day
- **Total: 3-4 days**

**Phase 3** (Write Operations):
- Create document: 0.5 day
- Delete document: 0.5 day
- Rename document: 0.5 day
- Permission revocation: 0.5 day
- Testing: 0.5 day
- **Total: 2-3 days**

**Grand Total: 7-10 development days**

---

## Conclusion

This plan outlines a phased approach to implementing DocumentsProvider for Butler:

1. **Phase 1** establishes the foundation with basic read-only access to internal storage
2. **Phase 2** expands to all Butler-accessible storage (SD, root, ADB)
3. **Phase 3** adds write operations for full document management

The architecture leverages Butler's existing file access infrastructure (APath, gateways, FileSystemOps) as a thin translation layer to Android's DocumentsProvider API. This approach:

- **Minimizes code duplication** - reuse existing battle-tested logic
- **Ensures consistency** - file operations behave same as in Butler UI
- **Simplifies maintenance** - improvements to gateways automatically benefit provider
- **Enables advanced features** - root/ADB access through standard system picker

The phased approach allows incremental delivery of value while managing complexity and risk. Each phase builds on the previous, with clear success criteria and testing requirements.

**Strategic Value**: This feature positions Butler as a **system-level file management solution**, enabling workflows impossible with standard file explorers (e.g., uploading root-accessible files to web services, sharing ADB-accessible files with apps).

**Next Steps**: Begin Phase 1 implementation following this plan, validating architectural decisions and adjusting as needed based on actual Butler codebase structure.
