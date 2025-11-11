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

**Format**: `{rootId}|{pathType}|{base64EncodedPath}`

**Components**:
- `rootId`: Which root this document belongs to (`internal`, `sdcard`, `root`, `adb`)
- `pathType`: Path type for decoding (`local`, `saf`, `root`, `adb`)
- `base64EncodedPath`: URL-safe Base64-encoded absolute path

**Examples**:
```kotlin
// Internal storage file
"internal|local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9Eb3dubG9hZC9maWxlLnBkZg"
// Decodes to: LocalPath("/storage/emulated/0/Download/file.pdf")

// Root-accessible system file
"root|root|L3N5c3RlbS9idWlsZC5wcm9w"
// Decodes to: RootPath("/system/build.prop")

// ADB-accessible Android/data
"adb|adb|L3N0b3JhZ2UvZW11bGF0ZWQvMC9BbmRyb2lkL2RhdGEvY29tLmV4YW1wbGUvZmlsZXMvZGF0YS50eHQ"
// Decodes to: ADBPath("/storage/emulated/0/Android/data/com.example/files/data.txt")
```

**Why This Design**:
- ✅ **Stable**: Paths are absolute, won't change unless file moves
- ✅ **Unique**: Combination of root + path type + path is guaranteed unique
- ✅ **Reversible**: Can reconstruct exact `APath` from Document ID
- ✅ **Safe**: Base64 encoding handles special characters, separators
- ✅ **Extensible**: Easy to add new path types in future

**Implementation**:
```kotlin
object DocumentIdCodec {
    private const val SEPARATOR = "|"

    fun encode(rootId: String, path: APath<*>): String {
        val pathType = when (path) {
            is LocalPath -> "local"
            is SAFPath -> "saf"
            // Future: RootPath, ADBPath
            else -> throw IllegalArgumentException("Unsupported path type: ${path::class}")
        }

        val encodedPath = Base64.encodeToString(
            path.path.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE
        )

        return "$rootId$SEPARATOR$pathType$SEPARATOR$encodedPath"
    }

    fun decode(documentId: String): Pair<String, APath<*>> {
        val parts = documentId.split(SEPARATOR)
        require(parts.size == 3) { "Invalid document ID format" }

        val (rootId, pathType, encodedPath) = parts
        val decodedPath = String(Base64.decode(encodedPath, Base64.URL_SAFE))

        val path = when (pathType) {
            "local" -> LocalPath(decodedPath)
            "saf" -> SAFPath.build(decodedPath) // May need URI parsing
            // Future: "root" -> RootPath(decodedPath)
            // Future: "adb" -> ADBPath(decodedPath)
            else -> throw IllegalArgumentException("Unknown path type: $pathType")
        }

        return rootId to path
    }
}
```

**Edge Cases Handled**:
- Special characters in paths (Base64 encoding)
- Very long paths (no length limit on Document IDs)
- Symbolic links (encode the link path itself, not target)
- Renamed files (renameDocument can return new ID)

**What About Renames?**:
When a file is renamed, `renameDocument()` is allowed to return a **new Document ID**. This is the one exception to ID stability. Our implementation:
1. Perform rename via gateway
2. Return new Document ID with updated path
3. System handles old → new ID migration for clients

### Root Configuration

**Root Types** (Sealed Class Hierarchy):

```kotlin
sealed class DocumentRoot {
    abstract val id: String
    abstract val icon: Int
    abstract val titleRes: Int
    abstract val summaryRes: Int?
    abstract val flags: Int
    abstract val path: APath<*>

    data object InternalStorage : DocumentRoot() {
        override val id = "internal"
        override val icon = R.drawable.ic_root_internal_storage
        override val titleRes = R.string.documents_root_internal_storage_title
        override val summaryRes = R.string.documents_root_internal_storage_summary
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val path = LocalPath("/storage/emulated/0")
    }

    data class ExternalStorage(
        val storagePath: String,
        val name: String
    ) : DocumentRoot() {
        override val id = "sdcard_${name.hashCode()}"
        override val icon = R.drawable.ic_root_sd_card
        override val titleRes = R.string.documents_root_external_storage_title
        override val summaryRes = null
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY or FLAG_SUPPORTS_EJECT
        override val path = LocalPath(storagePath)
    }

    data object RootAccess : DocumentRoot() {
        override val id = "root"
        override val icon = R.drawable.ic_root_root_access
        override val titleRes = R.string.documents_root_root_access_title
        override val summaryRes = R.string.documents_root_root_access_summary
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val path = LocalPath("/")  // Or RootPath("/")
    }

    data object ADBAccess : DocumentRoot() {
        override val id = "adb"
        override val icon = R.drawable.ic_root_adb_access
        override val titleRes = R.string.documents_root_adb_access_title
        override val summaryRes = R.string.documents_root_adb_access_summary
        override val flags = FLAG_SUPPORTS_IS_CHILD or FLAG_LOCAL_ONLY
        override val path = LocalPath("/storage/emulated/0")  // Or ADBPath
    }
}
```

**Root Manager** (Singleton):

```kotlin
@Singleton
class RootManager @Inject constructor(
    private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val settings: DocumentsProviderSettings,
    // Future: rootAvailability, adbAvailability checkers
) {
    suspend fun getAvailableRoots(): List<DocumentRoot> {
        val roots = mutableListOf<DocumentRoot>()

        // Internal storage - always available
        if (settings.showInternalStorage.value()) {
            roots.add(DocumentRoot.InternalStorage)
        }

        // External storage - detect SD cards
        if (settings.showExternalStorage.value()) {
            roots.addAll(detectExternalStorage())
        }

        // Root access - conditional on root availability
        if (settings.showRootAccess.value() && isRootAvailable()) {
            roots.add(DocumentRoot.RootAccess)
        }

        // ADB access - conditional on Shizuku availability
        if (settings.showADBAccess.value() && isADBAvailable()) {
            roots.add(DocumentRoot.ADBAccess)
        }

        return roots
    }

    private suspend fun detectExternalStorage(): List<DocumentRoot.ExternalStorage> {
        // Use Android's StorageManager to enumerate volumes
        // Filter for removable storage
        // Return list of ExternalStorage roots
        TODO("Implement SD card detection")
    }

    private suspend fun isRootAvailable(): Boolean {
        // Check if device is rooted and Butler has root access
        // May need to inject RootChecker or similar
        return false  // Phase 1: no root support
    }

    private suspend fun isADBAvailable(): Boolean {
        // Check if Shizuku is running and Butler has permission
        // May need to inject ShizukuChecker or similar
        return false  // Phase 1: no ADB support
    }

    fun getRootById(rootId: String): DocumentRoot? {
        return runBlocking { getAvailableRoots().find { it.id == rootId } }
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
                add(DocumentsContract.Root.COLUMN_ROOT_ID, root.id)
                add(DocumentsContract.Root.COLUMN_ICON, root.icon)
                add(DocumentsContract.Root.COLUMN_TITLE, context.getString(root.titleRes))
                add(DocumentsContract.Root.COLUMN_SUMMARY, root.summaryRes?.let { context.getString(it) })
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentIdCodec.encode(root.id, root.path))
                add(DocumentsContract.Root.COLUMN_FLAGS, root.flags)
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, getAvailableBytes(root.path))
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

        val (rootId, path) = DocumentIdCodec.decode(documentId)

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

        val (rootId, parentPath) = DocumentIdCodec.decode(parentDocumentId)

        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(resolvedProjection)

        try {
            // List children via gateway
            val children = gatewaySwitch.listFiles(
                parentPath,
                LookupOptions()
            )

            children.forEach { childLookup ->
                val childDocId = DocumentIdCodec.encode(rootId, childLookup.path)
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

        val (rootId, path) = DocumentIdCodec.decode(documentId)

        // Phase 1: Read-only support
        if (mode != "r") {
            throw UnsupportedOperationException("Write operations not yet supported")
        }

        // Check cancellation before expensive operation
        signal?.throwIfCanceled()

        return when (path) {
            is LocalPath -> openLocalPath(path, mode)
            is SAFPath -> openSAFPath(path, mode)
            // Future: RootPath, ADBPath
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
- Manual: Open Chrome on device, upload file, verify Butler appears in picker
- Manual: Browse internal storage through Butler provider
- Manual: Open files from Butler in various apps

**Estimated Effort**: 2-3 days

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

### Unit Testing

**Testable Components**:

1. **DocumentIdCodec**:
   ```kotlin
   @Test
   fun `encode and decode round trip`() {
       val original = LocalPath("/storage/emulated/0/Download/test.pdf")
       val encoded = DocumentIdCodec.encode("internal", original)
       val (rootId, decoded) = DocumentIdCodec.decode(encoded)

       assertEquals("internal", rootId)
       assertEquals(original, decoded)
   }

   @Test
   fun `handles special characters in paths`() {
       val original = LocalPath("/storage/test file (1) [copy].txt")
       val encoded = DocumentIdCodec.encode("internal", original)
       val (_, decoded) = DocumentIdCodec.decode(encoded)

       assertEquals(original, decoded)
   }
   ```

2. **RootManager**:
   - Mock settings and gateways
   - Verify root visibility logic
   - Test permission-based filtering

3. **Query Handlers**:
   - Mock GatewaySwitch
   - Verify cursor population
   - Test error handling

**Testing Challenges**:
- ContentProvider testing traditionally difficult
- Requires mocking Android framework classes
- Consider using Robolectric for Android API simulation

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
- Comprehensive unit tests for DocumentIdCodec
- Never use relative paths (always absolute)
- Document the one exception: rename operations
- Code review focus on ID generation changes

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
2. DocumentIdCodec has >90% test coverage
3. No memory leaks detected in profiler
4. Logging provides clear debugging trail

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
