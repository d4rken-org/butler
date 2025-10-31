# CreateOperation Refactoring Plan

**Date**: 2025-10-31
**Status**: Planned
**Objective**: Refactor `CreateOperation` to follow established architectural patterns used by `DeleteOperation`, `CopyOperation`, and `MoveOperation`.

---

## Problem Statement

### Current Architecture Inconsistency

`CreateOperation` currently has **all logic inline** within the operation class (~165 lines in `perform()` method), while other operations follow a **separation of concerns** pattern:

| Operation | Core Logic Location | Lines in perform() |
|-----------|-------------------|-------------------|
| DeleteOperation | Extension function `APath.delete()` | ~50-70 lines |
| CopyOperation | Extension function `APath.copy()` | ~50-70 lines |
| MoveOperation | Extension function `APath.move()` | ~50-70 lines |
| **CreateOperation** | **Inline in operation class** | **~165 lines** |

### What CreateOperation Does Wrong

The `CreateOperation.perform()` method contains:

1. **Manual conflict detection loop** (lines 80-168)
   - Checks if path exists via `gatewaySwitch.exists()`
   - Handles `PathAlreadyExists` with inline resolution logic
   - Implements rename loop for name conflicts

2. **Inline overwrite logic** (lines 111-157)
   - Manually calls `.delete()` when user chooses overwrite
   - Nested try-catch for delete failures
   - Manual retry loops

3. **Manual creation loop** (lines 174-211)
   - Direct `gatewaySwitch.createFile/createDir()` calls
   - Manual exception wrapping as `PathActionIssue.UnknownError`
   - Manual retry logic

4. **Mixed responsibilities**
   - Business logic mixed with UI state management
   - Progress tracking mixed with error handling
   - Not reusable outside operation context

### What Other Operations Do Right

**Example from DeleteOperation** (lines 76-95):
```kotlin
command.targets
    .delete(
        gateway = gatewaySwitch,
        options = DeleteAction.Options(
            recursive = true,
            onIssue = { issue ->
                emit(State.Waiting(...))
                issueHandler.handleIssue(operationContext.id, issue)
                emit(stateActive)
                resolution
            }
        )
    )
    .onEach { deleteState -> /* process progress states */ }
    .last()
```

**Key characteristics**:
- ✅ Delegates core logic to extension function
- ✅ Only handles UI state transitions (Active → Waiting → Active)
- ✅ Clean separation: operation orchestrates, extension executes
- ✅ Reusable logic (extension can be called from anywhere)
- ✅ Testable in isolation

---

## Established Patterns in Codebase

### Layer 1: Action Interface

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/actions/`

**Purpose**: Define contract for file operations

**Pattern**:
```kotlin
interface DeleteAction<P : APath<P>, PL : APathLookup<P>> {
    suspend fun delete(
        targets: Set<P>,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath<P>>(
        val recursive: Boolean = false,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<out P, out PL> {
        data class Active<out P, out PL>(...) : State<P, PL>
        data class Completed<out P, out PL>(...) : State<P, PL>
    }
}
```

**Files**:
- `DeleteAction.kt` ✅ Exists
- `CopyAction.kt` ✅ Exists
- `MoveAction.kt` ✅ Exists
- `CreateAction.kt` ❌ **Needs to be created**

### Layer 2: Generic Implementation

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/operations/`

**Purpose**: Path-type-agnostic implementation of action

**Pattern**:
```kotlin
internal class GenericPathDelete<P : APath<P>, PL : APathLookup<P>>(
    private val targets: Collection<P>,
    private val fileSystemOps: FileSystemOps<P, PL>,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    // Shared components
    private val progressTracker = PathOperationProgressTracker()
    private val issueResolver = PathOperationIssueResolver(onIssue)
    private val errorHandler = TransferErrorHandler()

    fun execute(): Flow<DeleteAction.State<P, PL>> = flow {
        // Implementation with work queue, progress tracking, issue handling
    }
}

// Extension function for convenience
fun <P : APath<P>, PL : APathLookup<P>> Collection<P>.deleteGeneric(
    fileSystemOps: FileSystemOps<P, PL>,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<DeleteAction.State<P, PL>> = GenericPathDelete(...).execute()
```

**Files**:
- `GenericPathDelete.kt` ✅ Exists
- `GenericPathCopy.kt` ✅ Exists
- `GenericPathMove.kt` ✅ Exists
- `GenericPathCreate.kt` ❌ **Needs to be created**

**Shared Components** (already exist, will reuse):
- `PathOperationProgressTracker` - Throttled progress reporting
- `PathOperationIssueResolver` - "Apply to all" support for issue resolutions
- `TransferErrorHandler` - Consistent error handling and retry logic

### Layer 3: Gateway Extensions

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/extensions/APathGatewayExtensions.kt`

**Purpose**: User-friendly API for operations

**Pattern**:
```kotlin
// Single item version
suspend fun <P : APath<P>, PL : APathLookup<P>> P.delete(
    gateway: APathGateway<P, PL>,
    options: DeleteAction.Options<P>,
) = setOf(this).delete(gateway, options)

// Set version
suspend fun <P : APath<P>, PL : APathLookup<P>> Set<P>.delete(
    gateway: APathGateway<P, PL>,
    options: DeleteAction.Options<P>,
): Flow<DeleteAction.State<P, PL>> = gateway.delete(
    targets = this,
    options = options
).onCompletion {
    log(VERBOSE) { "Set<APath>.delete(): Deleted $this" }
}
```

**Current state**:
- `delete()` extensions ✅ Exist
- `copy()` extensions ✅ Exist
- `move()` extensions ✅ Exist
- `create()` extensions ❌ **Need to be added**

### Layer 4: Workspace Operation

**Location**: `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/`

**Purpose**: Workspace-level orchestration with UI state management

**Responsibilities**:
- Metadata (title, description, icon)
- Issue handler callbacks (wrap issues in `State.Waiting`)
- Progress state processing (calculate metrics, format UI strings)
- Report building
- FileSystemHinter tracking (for UI updates)

**Pattern** (from DeleteOperation):
```kotlin
override fun perform(operationContext: Operation.Context): Flow<State> = flow {
    var stateActive = State.Active(startedAt = operationContext.startedAt)
    emit(stateActive)

    val reportBuilder = DeleteOperationReport.Builder()

    command.targets
        .delete(
            gateway = gatewaySwitch,
            options = DeleteAction.Options(
                onIssue = { issue ->
                    emit(State.Waiting(...))
                    val resolution = issueHandler.handleIssue(...)
                    emit(stateActive)
                    resolution
                }
            )
        )
        .onEach { deleteState ->
            // Process progress, update stateActive, emit
        }
        .last()

    // Track filesystem changes
    fileSystemHinter.trackPathsRemoved(...)

    // Build report
    reportBuilder.setDeletions(...)

    emit(State.Completed(...))
}
```

---

## Refactoring Plan

### Phase 1: Create Action Interface

**File**: `app-common-io/src/main/java/eu/darken/butler/common/files/actions/CreateAction.kt` (new)

**Rationale**: Create operations are simpler than copy/move/delete:
- No scanning phase needed
- No recursion needed
- Single target (not a collection)
- Instant operation (no progress tracking needed in generic layer)
- Conflict detection via lookup (not exceptions)

**Implementation**:
```kotlin
package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow

interface CreateAction<P : APath<P>, PL : APathLookup<P>> {
    suspend fun create(
        target: P,
        type: CreateType,
        options: Options = Options()
    ): Flow<State<P, PL>>

    enum class CreateType {
        FILE,
        DIRECTORY
    }

    data class Options(
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<out P, out PL> {
        /**
         * Active state - creation in progress
         * Note: Create operations are typically instant, but this state exists
         * for consistency with other actions and to support async operations
         * on some filesystems (e.g., SAF, network paths)
         */
        data class Active<out P, out PL>(
            val target: P,
            val type: CreateType,
        ) : State<P, PL>

        /**
         * Completed state - path successfully created
         */
        data class Completed<out P, out PL>(
            val created: PL,
        ) : State<P, PL>
    }
}
```

**Key differences from other actions**:
- Single `target: P` instead of `targets: Set<P>` (no bulk operations)
- No progress tracking fields in `Active` state (instant operation)
- `CreateType` enum for file vs directory distinction
- Simpler `Completed` state (just the created lookup, no skipped items)

### Phase 2: Implement Generic Create Operation

**File**: `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathCreate.kt` (new)

**Logic Flow**:
```
1. Emit Active state
2. Check if target exists (via lookup)
3. If exists:
   a. Create PathAlreadyExists issue
   b. Call onIssue callback
   c. Handle resolution:
      - RenameSource: Update target path, goto step 2
      - Overwrite: Delete existing path, goto step 4
      - Cancel: Throw CancellationException
4. Create file/directory via fileSystemOps
5. Handle creation errors:
   - Wrap as UnknownError issue
   - Call onIssue callback
   - Retry if user chooses
6. Lookup created path
7. Emit Completed state
```

**Implementation**:
```kotlin
package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

internal class GenericPathCreate<P : APath<P>, PL : APathLookup<P>>(
    private val target: P,
    private val type: CreateAction.CreateType,
    private val fileSystemOps: FileSystemOps<P, PL>,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val tag = logTag("FileOps", "Generic", "Create")
    private val issueResolver = PathOperationIssueResolver(onIssue)
    private val errorHandler = TransferErrorHandler()

    fun execute(): Flow<CreateAction.State<P, PL>> = flow {
        log(tag) { "execute(): target=$target, type=$type" }

        emit(CreateAction.State.Active(target, type))

        var currentTarget = target

        // Loop to handle conflicts with rename resolution
        while (true) {
            // Check if target already exists
            val existingLookup = fileSystemOps.lookup(
                currentTarget,
                LookupOptions(fallbackToUnknown = true)
            )

            if (existingLookup.fileType != FileType.UNKNOWN) {
                log(tag) { "Target already exists: $existingLookup" }

                val issue = PathActionIssue.PathAlreadyExists(
                    destination = existingLookup,
                    canRenameSource = true,
                    canOverwrite = true,
                )

                when (val resolution = issueResolver.resolve(issue)) {
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                        log(tag) { "Renaming to: ${resolution.newName}" }
                        // Update target with new name
                        currentTarget = currentTarget.parent?.child(resolution.newName)
                            ?: throw IllegalStateException("Cannot rename root path")
                        // Continue loop to check new name
                        continue
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                        log(tag) { "Overwriting existing path" }
                        // Delete existing path first
                        currentTarget.deleteGeneric(
                            fileSystemOps = fileSystemOps,
                            recursive = true,
                            onIssue = onIssue
                        ).last()
                        // Exit conflict loop, proceed to creation
                        break
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                        throw CancellationException("Create operation cancelled by user")
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                        throw IllegalArgumentException("RenameDestination not supported for create")
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                        throw IllegalArgumentException("Merge not supported for create")
                    }
                    is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                        throw IllegalStateException("Skip not supported for create (canSkip=false)")
                    }
                }
            } else {
                // Path doesn't exist, proceed to creation
                break
            }
        }

        // Create file or directory with retry logic
        errorHandler.withRetry(
            onIssue = onIssue,
            operation = {
                when (type) {
                    CreateAction.CreateType.FILE -> {
                        log(tag) { "Creating file: $currentTarget" }
                        fileSystemOps.createFile(currentTarget)
                    }
                    CreateAction.CreateType.DIRECTORY -> {
                        log(tag) { "Creating directory: $currentTarget" }
                        fileSystemOps.createDir(currentTarget)
                    }
                }
            },
            errorContext = {
                PathActionIssue.UnknownError(
                    exception = it,
                    errorMessage = (it.message ?: it.toString()).toCaString(),
                    destination = fileSystemOps.lookup(
                        currentTarget,
                        LookupOptions(fallbackToUnknown = true)
                    ),
                    canRetry = true,
                    canSkip = false,
                )
            }
        )

        // Lookup the created path
        val created = fileSystemOps.lookup(currentTarget, LookupOptions.BASE)
        log(tag) { "Created: $created" }

        emit(CreateAction.State.Completed(created))
    }
}

/**
 * Extension function for creating a file or directory
 */
fun <P : APath<P>, PL : APathLookup<P>> P.createGeneric(
    fileSystemOps: FileSystemOps<P, PL>,
    type: CreateAction.CreateType,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CreateAction.State<P, PL>> = GenericPathCreate(
    target = this,
    type = type,
    fileSystemOps = fileSystemOps,
    onIssue = onIssue
).execute()
```

**Key design decisions**:
1. **No work queue**: Create is single-item operation, no need for queue complexity
2. **No progress tracking**: Create is typically instant (no bytes transferred)
3. **Conflict detection via lookup**: Unlike copy/move which detect conflicts during execution
4. **Rename loop**: Handle rename resolution by updating target and rechecking
5. **Reuse existing components**: `PathOperationIssueResolver`, `TransferErrorHandler`
6. **Reuse delete for overwrite**: Call existing `deleteGeneric()` instead of reimplementing

### Phase 3: Add Gateway Support

#### Step 3.1: Update APathGateway Interface

**File**: `app-common-io/src/main/java/eu/darken/butler/common/files/APathGateway.kt` (modify)

Add method:
```kotlin
interface APathGateway<P : APath<P>, PL : APathLookup<P>> : CreateAction<P, PL> {
    // ... existing methods ...

    override suspend fun create(
        target: P,
        type: CreateAction.CreateType,
        options: CreateAction.Options
    ): Flow<CreateAction.State<P, PL>>
}
```

#### Step 3.2: Add Gateway Extensions

**File**: `app-common-io/src/main/java/eu/darken/butler/common/files/extensions/APathGatewayExtensions.kt` (modify)

Add extensions:
```kotlin
/**
 * Create a file or directory at this path
 */
suspend fun <P : APath<P>, PL : APathLookup<P>> P.create(
    gateway: APathGateway<P, PL>,
    type: CreateAction.CreateType,
    options: CreateAction.Options = CreateAction.Options(),
): Flow<CreateAction.State<P, PL>> = gateway.create(
    target = this,
    type = type,
    options = options
).onCompletion {
    log(VERBOSE) { "P.create(type=$type): Created $this" }
}
```

#### Step 3.3: Implement in Gateway Classes

**Files to modify**:
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalGateway.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/saf/SAFGateway.kt`
- (Any other gateway implementations)

**Pattern for LocalGateway**:
```kotlin
override suspend fun create(
    target: LocalPath,
    type: CreateAction.CreateType,
    options: CreateAction.Options
): Flow<CreateAction.State<LocalPath, LocalPathLookup>> = target.createGeneric(
    fileSystemOps = this,
    type = type,
    onIssue = options.onIssue
)
```

### Phase 4: Refactor CreateOperation

**File**: `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/CreateOperation.kt` (modify)

**Before** (~165 lines in perform()):
```kotlin
override fun perform(operationContext: Operation.Context): Flow<State> = flow {
    gatewaySwitch.useRes {
        // ... 165 lines of inline logic ...
        // - Manual conflict detection loop
        // - Inline issue handling
        // - Nested try-catch blocks
        // - Manual retry loops
        // - Direct createFile/createDir calls
    }
}
```

**After** (~50-70 lines in perform()):
```kotlin
override fun perform(operationContext: Operation.Context): Flow<State> = flow {
    log(tag) { "perform(): $command" }

    var stateActive = State.Active(startedAt = operationContext.startedAt)
    emit(stateActive)

    val reportBuilder = CreateOperationReport.Builder()

    val targetPath = command.parentPath.child(command.name)
    val createType = when (command.type) {
        ExplorerCommand.Create.Type.FILE -> CreateAction.CreateType.FILE
        ExplorerCommand.Create.Type.DIRECTORY -> CreateAction.CreateType.DIRECTORY
    }

    val result = targetPath
        .create(
            gateway = gatewaySwitch,
            type = createType,
            options = CreateAction.Options(
                onIssue = { issue ->
                    emit(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )
                    val resolution = issueHandler.handleIssue(
                        operationContext.id,
                        issue
                    ) as PathActionIssue.Resolution
                    emit(stateActive)
                    resolution
                }
            )
        )
        .last()

    result as CreateAction.State.Completed<*, *>

    // Track filesystem changes
    fileSystemHinter.trackPathsAdded(operationContext.id, setOf(result.created))

    // Build report
    reportBuilder.addPathEvent(
        FileSystemEvent.Added(
            operationId = operationContext.id,
            paths = setOf(result.created)
        )
    )

    emit(
        State.Completed(
            startedAt = operationContext.startedAt,
            report = reportBuilder.build()
        )
    )
}
```

**Changes**:
- ❌ Remove manual conflict detection loop (now in generic implementation)
- ❌ Remove inline issue handling logic (delegated via callback)
- ❌ Remove nested try-catch blocks (handled by TransferErrorHandler)
- ❌ Remove manual retry loops (handled by errorHandler.withRetry)
- ❌ Remove direct gatewaySwitch calls (use extension function)
- ✅ Add call to `.create()` extension
- ✅ Keep issue handler callback (wraps issues in State.Waiting)
- ✅ Keep FileSystemHinter tracking (for UI updates)
- ✅ Keep report building (for operation results)

**Reduction**: ~165 lines → ~50-70 lines (~70% reduction)

### Phase 5: Add Tests

#### Test 5.1: Generic Implementation Tests

**File**: `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathCreateTest.kt` (new)

**Test cases**:
```kotlin
@Test
fun `create file when path doesn't exist`() {
    // Given: Path doesn't exist
    // When: Create file
    // Then: File created successfully
}

@Test
fun `create directory when path doesn't exist`() {
    // Given: Path doesn't exist
    // When: Create directory
    // Then: Directory created successfully
}

@Test
fun `handle conflict with rename resolution`() {
    // Given: Path already exists
    // When: User chooses rename
    // Then: New path created with different name
}

@Test
fun `handle conflict with overwrite resolution`() {
    // Given: Path already exists
    // When: User chooses overwrite
    // Then: Existing path deleted, new path created
}

@Test
fun `handle conflict with cancel resolution`() {
    // Given: Path already exists
    // When: User chooses cancel
    // Then: CancellationException thrown
}

@Test
fun `retry on creation error`() {
    // Given: First create attempt fails
    // When: User chooses retry
    // Then: Second attempt succeeds
}

@Test
fun `cancel on creation error`() {
    // Given: Create attempt fails
    // When: User chooses cancel
    // Then: CancellationException thrown
}

@Test
fun `handle multiple rename conflicts`() {
    // Given: Multiple paths exist (file.txt, file (1).txt)
    // When: User renames multiple times
    // Then: Eventually finds available name
}
```

**Mock setup**:
```kotlin
private val fileSystemOps = mockk<FileSystemOps<TestPath, TestPathLookup>>()
private var onIssueCalled = false
private var issueResolution: PathActionIssue.Resolution? = null

private val onIssue: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
    onIssueCalled = true
    issueResolution ?: throw AssertionError("No resolution provided")
}
```

#### Test 5.2: Integration Tests

**File**: `app-common-io/src/test/java/eu/darken/butler/common/files/local/LocalPathCreateTest.kt` (new)

**Test cases**:
```kotlin
@Test
fun `create file in temp directory`() = runTest {
    // Real filesystem test
}

@Test
fun `create directory in temp directory`() = runTest {
    // Real filesystem test
}

@Test
fun `overwrite existing file`() = runTest {
    // Real filesystem test with conflict
}
```

#### Test 5.3: Operation Tests

Update existing test file if it exists, or create new:
**File**: `app-workspace-explorer/src/test/java/eu/darken/butler/explorer/core/operations/CreateOperationTest.kt`

**Focus**: Test operation-level concerns (metadata, report building, FileSystemHinter tracking)

---

## Implementation Checklist

### Phase 1: Core Action Interface
- [ ] Create `CreateAction.kt`
- [ ] Define `CreateType` enum
- [ ] Define `Options` data class
- [ ] Define `State` sealed interface with `Active` and `Completed`
- [ ] Add KDoc comments

### Phase 2: Generic Implementation
- [ ] Create `GenericPathCreate.kt`
- [ ] Implement conflict detection via lookup
- [ ] Implement rename resolution loop
- [ ] Implement overwrite via delete
- [ ] Integrate `PathOperationIssueResolver`
- [ ] Integrate `TransferErrorHandler` for retry logic
- [ ] Add `createGeneric()` extension function
- [ ] Add logging

### Phase 3: Gateway Support
- [ ] Update `APathGateway` interface
- [ ] Add `create()` extensions to `APathGatewayExtensions.kt`
- [ ] Implement in `LocalGateway`
- [ ] Implement in `SAFGateway`
- [ ] Implement in other gateway classes (if any)

### Phase 4: Refactor CreateOperation
- [ ] Replace inline logic with `.create()` call
- [ ] Remove manual conflict detection loop
- [ ] Remove inline issue handling
- [ ] Remove nested try-catch blocks
- [ ] Remove manual retry loops
- [ ] Keep issue handler callback
- [ ] Keep FileSystemHinter tracking
- [ ] Keep report building
- [ ] Update imports

### Phase 5: Testing
- [ ] Create `GenericPathCreateTest.kt`
- [ ] Write unit tests for all scenarios
- [ ] Create `LocalPathCreateTest.kt`
- [ ] Write integration tests
- [ ] Update/create `CreateOperationTest.kt`
- [ ] Run all tests and verify passing

### Phase 6: Verification
- [ ] Build module: `./gradlew :app-common-io:compileDebugKotlin`
- [ ] Build explorer module: `./gradlew :app-workspace-explorer:compileDebugKotlin`
- [ ] Run tests: `./gradlew :app-common-io:testDebugUnitTest`
- [ ] Manual testing in app (create files/folders)
- [ ] Test conflict scenarios (rename, overwrite)
- [ ] Test error scenarios (permissions, disk space)

---

## Benefits

### 1. Architectural Consistency
- ✅ All operations (delete, copy, move, create) follow same pattern
- ✅ Predictable code structure for maintainers
- ✅ Easier to add new operations in the future

### 2. Code Reusability
- ✅ Generic implementation works for all path types (Local, SAF, etc.)
- ✅ Can be used outside workspace operations (e.g., in tests, utilities)
- ✅ Shared components reduce duplication

### 3. Improved Testability
- ✅ Can test `GenericPathCreate` in isolation with mocks
- ✅ Easier to test edge cases (conflicts, errors, retries)
- ✅ Operation tests can focus on orchestration, not business logic

### 4. Better Maintainability
- ✅ Separation of concerns (business logic vs UI state management)
- ✅ Centralized error handling and retry logic
- ✅ Easier to debug (smaller, focused functions)
- ✅ Less code to maintain (~115 lines moved out of operation)

### 5. Enhanced Extensibility
- ✅ Easy to add new options (e.g., `createParents`, `failIfExists`)
- ✅ Can add progress tracking if needed for slow filesystems
- ✅ Can extend for advanced scenarios (templates, metadata)

---

## Risks and Mitigations

### Risk 1: Breaking Changes
**Impact**: Existing code might rely on current implementation
**Mitigation**:
- This is internal refactoring, no public API changes
- `CreateOperation` interface remains the same
- Thorough testing before merge

### Risk 2: Behavior Changes
**Impact**: Subtle differences in error handling or issue resolution
**Mitigation**:
- Follow exact logic from current implementation
- Test all conflict scenarios (rename, overwrite, cancel)
- Test all error scenarios (permissions, disk space, unknown)
- Manual testing in app

### Risk 3: Performance Regression
**Impact**: Additional layers might add overhead
**Mitigation**:
- Create is already instant operation, no performance impact expected
- Extension function is inlined by compiler
- Same number of filesystem calls (lookup, create)

### Risk 4: Test Coverage Gaps
**Impact**: Might miss edge cases in refactoring
**Mitigation**:
- Comprehensive test plan (see Phase 5)
- Test matrix covering all combinations
- Integration tests with real filesystem

---

## Future Enhancements

Once refactoring is complete, these enhancements become easier:

1. **Create with parents**: Option to create parent directories automatically
2. **Template support**: Create files from templates with variable substitution
3. **Bulk create**: Create multiple files/directories in one operation
4. **Metadata preservation**: Set creation time, permissions, etc.
5. **Symlink support**: Add `CreateType.SYMLINK`
6. **Progress for slow filesystems**: Add progress tracking for network paths
7. **Dry run mode**: Preview what would be created without actually creating

---

## References

### Existing Implementation Files
- `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/CreateOperation.kt`
- `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/DeleteOperation.kt`
- `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/CopyOperation.kt`
- `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/MoveOperation.kt`

### Pattern Files
- `app-common-io/src/main/java/eu/darken/butler/common/files/actions/DeleteAction.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/actions/CopyAction.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/actions/MoveAction.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathDelete.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathCopy.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathMove.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/extensions/APathGatewayExtensions.kt`

### Shared Components
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/PathOperationProgressTracker.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/PathOperationIssueResolver.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/TransferErrorHandler.kt`

---

## Notes

- This refactoring was planned on 2025-10-31
- The pattern analysis revealed CreateOperation as the only outlier
- All other operations already follow the established pattern
- The refactoring brings CreateOperation in line with codebase standards
