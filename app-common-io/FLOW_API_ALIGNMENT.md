# Flow API Alignment Plan

## Current Architecture Analysis

There are **two API layers** with different signatures for copy/move operations:

### 1. Interface Layer (Flow-based) - CopyAction/MoveAction

```kotlin
interface CopyAction<P : APath<P>, PL : APathLookup<P>> : GatewayAction<P> {
    suspend fun copy(
        sources: Set<P>,
        destination: P,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>  // Returns Flow
}
```

### 2. Implementation Layer (Callback-based) - GenericPathCopy/GenericPathMove

```kotlin
internal class GenericPathCopy<...>(
    private val sources: Collection<SP>,
    private val destination: DP,
    private val sourceOps: FileSystemOps<SP, SPL, SPLE>,
    private val destOps: FileSystemOps<DP, DPL, DPLE>,
    private val strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    private val options: TransferStrategy.Options,
    private val onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)?,  // Callback
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    suspend fun execute(): CopyAction.State.Result<SP, SPL>  // Returns Result directly
}
```

### 3. Bridge Layer - LocalGateway

LocalGateway implements the Flow-based interface but delegates to callback-based implementations:

```kotlin
// LocalGateway.kt:708
override suspend fun copy(...): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = flow {
    val result = sources.copy(
        fileSystemOps = fileSystemOps,
        destination = destination,
        options = options,
        onProgress = { progress -> emit(progress) },  // Bridge: callback → flow
        onIssue = onIssue,
    )
    emit(result)
}
```

## The Problem

**Yes, there is a signature mismatch**, currently bridged in LocalGateway:

- **Interfaces** define Flow-based API (progress as flow emissions)
- **Generic operations** use callback-based API (progress via callbacks)
- **Gateway** bridges the two by converting callbacks to flow emissions

## Recommendation: YES - Align to Flow Throughout

### Benefits of Alignment:

1. **Consistency**: One API pattern throughout the entire stack
    - Easier to understand and maintain
    - No need to remember which layer uses which pattern

2. **Eliminates bridge code**: LocalGateway wouldn't need conversion logic like `onProgress = { emit() }`

3. **Better composability**: Consumers could use Flow operators directly
   ```kotlin
   val result = GenericPathCopy(...).execute()
       .filter { it is Progress }  // Could filter/transform progress
       .debounce(250.milliseconds)  // Could debounce progress updates
       .collect { state -> /* ... */ }
   ```

4. **Modern Kotlin idioms**: Flow is the standard for async streams in Kotlin coroutines

5. **Simpler for callers**: No need to understand two different patterns

## Migration Plan

### 1. GenericPathCopy.kt & GenericPathMove.kt

**Changes:**

- Remove `onProgress` callback parameter from constructor
- Change `execute()` signature from `suspend fun execute(): Result` to `fun execute(): Flow<State>`
- Replace `onProgress?.invoke(progress)` calls with `emit(progress)` inside a flow builder
- Emit final result as last flow element

**Before:**

```kotlin
internal class GenericPathCopy<...>(
    // ... other params ...
    private val onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)?,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    suspend fun execute(): CopyAction.State.Result<SP, SPL> {
        // ... work ...
        onProgress?.invoke(progress)
        // ... more work ...
        return CopyAction.State.Result(...)
    }
}
```

**After:**

```kotlin
internal class GenericPathCopy<...>(
    // ... other params ...
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    fun execute(): Flow<CopyAction.State<SP, SPL>> = flow {
        // ... work ...
        emit(CopyAction.State.Progress(...))  // Instead of onProgress?.invoke()
        // ... more work ...
        emit(CopyAction.State.Result(...))    // Final result as flow emission
    }
}
```

### 2. Extension Functions (copyGeneric/moveGeneric)

**File:** `GenericPathCopy.kt` (lines 870-890)

**Before:**

```kotlin
suspend fun <...> Collection<SP>.copyGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL, SPLE>,
    destOps: FileSystemOps<DP, DPL, DPLE>,
    strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    options: TransferStrategy.Options = TransferStrategy.Options(),
    onProgress: (suspend (CopyAction.State.Progress<SP, SPL>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<SP, SPL> = GenericPathCopy(...).execute()
```

**After:**

```kotlin
fun <...> Collection<SP>.copyGeneric(
    destination: DP,
    sourceOps: FileSystemOps<SP, SPL, SPLE>,
    destOps: FileSystemOps<DP, DPL, DPLE>,
    strategy: TransferStrategy<SP, SPL, SPLE, DP, DPL, DPLE>,
    options: TransferStrategy.Options = TransferStrategy.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<SP, SPL>> = GenericPathCopy(...).execute()
```

### 3. Convenience Functions (LocalPathOperations.kt)

**File:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathOperations.kt`

**Before:**

```kotlin
suspend fun Collection<LocalPath>.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    // ...
    return this.copyGeneric(..., onProgress = onProgress, ...)
}
```

**After:**

```kotlin
fun Collection<LocalPath>.copyGenericOp(
    destination: LocalPath,
    fileSystemOps: LocalFileSystemOps,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> {
    // ...
    return this.copyGeneric(..., onIssue = onIssue)
}
```

### 4. LocalGateway.kt

**File:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalGateway.kt` (lines 685-755)

**Before:**

```kotlin
override suspend fun copy(
    sources: Set<LocalPath>,
    destination: LocalPath,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    options: CopyAction.Options<LocalPath>
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = flow {
    val result = sources.copy(
        fileSystemOps = fileSystemOps,
        destination = destination,
        options = options,
        onProgress = { progress -> emit(progress) },  // Bridge code
        onIssue = onIssue,
    )
    emit(result)
}
```

**After:**

```kotlin
override suspend fun copy(
    sources: Set<LocalPath>,
    destination: LocalPath,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    options: CopyAction.Options<LocalPath>
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = sources.copy(
    fileSystemOps = fileSystemOps,
    destination = destination,
    options = options,
    onIssue = onIssue,
)  // Directly return the Flow, no bridging needed
```

### 5. LocalPathCopy.kt & LocalPathMove.kt

**Files:**

- `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathCopy.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathMove.kt`

**Before:**

```kotlin
suspend fun Collection<LocalPath>.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    return this.copyGenericOp(destination, fileSystemOps, options, onProgress, onIssue)
}
```

**After:**

```kotlin
fun Collection<LocalPath>.copy(
    fileSystemOps: LocalFileSystemOps,
    destination: LocalPath,
    options: CopyAction.Options<LocalPath> = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> {
    return this.copyGenericOp(destination, fileSystemOps, options, onIssue)
}
```

### 6. Tests

**Files to update:**

- `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathCopyTest.kt`
- `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathMoveTest.kt`

**Changes:**

- Update to collect Flows instead of using callbacks
- Consider using Turbine library for Flow testing (cleaner assertions)

**Before:**

```kotlin
val progressUpdates = mutableListOf<CopyAction.State.Progress<...>>()
val result = sources.copyGeneric(
    onProgress = { progressUpdates.add(it) }
)
```

**After (option 1 - manual):**

```kotlin
val states = sources.copyGeneric(...).toList()
val progressUpdates = states.filterIsInstance<CopyAction.State.Progress<...>>()
val result = states.last() as CopyAction.State.Result
```

**After (option 2 - with Turbine):**

```kotlin
sources.copyGeneric(...).test {
    val progress1 = awaitItem() as CopyAction.State.Progress
    val progress2 = awaitItem() as CopyAction.State.Progress
    val result = awaitItem() as CopyAction.State.Result
    awaitComplete()
}
```

## Files Affected

1. `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathCopy.kt`
2. `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathMove.kt`
3. `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathOperations.kt`
4. `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathCopy.kt`
5. `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalPathMove.kt`
6. `app-common-io/src/main/java/eu/darken/butler/common/files/local/LocalGateway.kt`
7. `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathCopyTest.kt`
8. `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathMoveTest.kt`

## Implementation Order

1. **Start with GenericPathCopy and GenericPathMove** - Core implementation
2. **Update extension functions** - copyGeneric/moveGeneric
3. **Update LocalPathOperations** - Convenience wrappers
4. **Update LocalPathCopy/LocalPathMove** - Public API
5. **Update LocalGateway** - Remove bridge code
6. **Update tests** - Verify everything works
7. **Consider adding Turbine** - If Flow testing becomes cumbersome

## Testing Strategy

- Run existing tests after each layer change
- Verify progress emissions work correctly
- Ensure final result is emitted as last element
- Test cancellation behavior (Flow should stop on cancel)
- Test error propagation through Flow

## Potential Concerns

1. **Breaking changes**: This changes public API signatures (removes `onProgress` callback)
    - Mitigation: Only affects internal code within `app-common-io` module

2. **Performance**: Flow might add overhead
    - Mitigation: Flow is designed for this use case, overhead should be minimal

3. **Complexity**: Flow testing can be more complex
    - Mitigation: Consider using Turbine library for cleaner test code

## Alternative: Keep Both Patterns

If Flow alignment is deemed too risky, an alternative is to:

1. Keep the current callback-based implementation
2. Document why the two patterns exist
3. Accept the bridge code in LocalGateway as necessary architectural cost

However, this is not recommended because:

- It creates cognitive overhead for maintainers
- It prevents leveraging Flow operators for progress handling
- It goes against Kotlin/coroutines best practices
