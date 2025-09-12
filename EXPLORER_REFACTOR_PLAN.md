# Explorer Architecture Refactoring Plan

## Overview

This document outlines the plan to refactor the Butler Explorer workspace to support real-time file system updates with optimistic UI changes and eventual consistency through file system watching.

## Current State Analysis

### Existing Components

#### ExplorerEngine
- **Responsibilities**: Directory navigation, file listing, shortcuts
- **Current Issues**: Contains unused `executeOperation()` method
- **Strengths**: Well-structured navigation logic, efficient file discovery

#### OperationExecutor
- **Responsibilities**: File operations (copy, move, delete) with state tracking
- **Current Issues**: Placeholder implementations for actual file operations
- **Strengths**: Complete operation state system with conflict resolution

#### ExplorerWorkspace
- **Responsibilities**: Orchestrates navigation and operations
- **Current Issues**: Coordination between separate engines
- **Strengths**: Clean state management, integrated conflict handling

### Current Pain Points

1. **Cache Inconsistency**: No mechanism to update directory views when external changes occur
2. **Coordination Complexity**: Two separate engines require careful coordination
3. **Optimistic Updates**: Operations don't immediately reflect in directory views
4. **Multiple Writers**: No handling for external file system changes
5. **Performance**: Full directory refresh after operations

### What's Working Well

1. **Operation State System**: Complete flow for operation tracking
2. **Conflict Resolution**: User-interactive conflict handling
3. **Progress Tracking**: Real-time operation progress and metrics
4. **Type Safety**: Strong typing with OperationId and state types
5. **Clean Separation**: Clear responsibilities between components

## Target Architecture

### Core Principles

1. **Modular Design**: Keep BrowsingEngine and OperationEngine separate
2. **Event-Driven Coordination**: Components communicate via events, not tight coupling
3. **Optimistic Updates**: Operations provide hints for immediate UI updates
4. **File System Watching**: FileObserver catches all changes (internal and external)
5. **Eventual Consistency**: File system is the ultimate source of truth

### Component Responsibilities

#### BrowsingEngine (ExplorerEngine renamed)
```kotlin
class BrowsingEngine {
    // Directory state cache with file system watcher integration
    private val directories: Map<APath, DirectoryState>
    private val fileWatcher: FileSystemWatcher
    
    fun browse(path: APath): Flow<DirectoryContent>
    fun acceptOperationHint(hint: OperationHint)
    fun refreshDirectory(path: APath)
}
```

**Responsibilities:**
- Directory navigation and listing
- File system change detection via FileObserver
- Optimistic update application
- Directory state caching and invalidation

#### OperationEngine (OperationExecutor renamed)
```kotlin
class OperationEngine {
    fun execute(operation: Operation): Flow<OperationState>
    fun resolveConflict(operationId: OperationId, resolution: ConflictResolution)
    fun cancelOperation(operationId: OperationId)
    
    val operationHints: Flow<OperationHint> // For BrowsingEngine
}
```

**Responsibilities:**
- Execute file operations with real implementations
- Emit operation state changes
- Provide hints to BrowsingEngine for optimistic updates
- Handle conflicts and cancellations

#### FileSystemWatcher
```kotlin
class FileSystemWatcher {
    fun watch(path: APath): Flow<FileSystemEvent>
    fun startWatching(path: APath)
    fun stopWatching(path: APath)
}

sealed class FileSystemEvent {
    data class FileCreated(val path: APath)
    data class FileDeleted(val path: APath)
    data class FileModified(val path: APath)
    data class DirectoryChanged(val path: APath, val changeType: ChangeType)
    data class MassiveChange(val path: APath) // Too many changes, refresh needed
}
```

**Responsibilities:**
- Android FileObserver integration
- Event filtering and batching
- Graceful degradation when watching fails

#### OperationHint System
```kotlin
sealed class OperationHint {
    abstract val targetPath: APath
    abstract val timestamp: Instant
    
    data class FilesAdded(
        override val targetPath: APath,
        val files: List<APath>,
        override val timestamp: Instant = now()
    ) : OperationHint()
    
    data class FilesRemoved(
        override val targetPath: APath, 
        val files: List<APath>,
        override val timestamp: Instant = now()
    ) : OperationHint()
    
    data class FileRenamed(
        override val targetPath: APath,
        val oldName: String,
        val newName: String,
        override val timestamp: Instant = now()
    ) : OperationHint()
}
```

## Implementation Phases

### Phase 1: Cleanup and Rename (Foundation)
**Timeline: 1-2 days**

#### 1.1 Rename Components
- [ ] Rename `ExplorerEngine` → `BrowsingEngine`
- [ ] Rename `OperationExecutor` → `OperationEngine`
- [ ] Update all imports and references
- [ ] Add class-level documentation

#### 1.2 Remove Dead Code
- [ ] Remove unused `executeOperation()` from BrowsingEngine
- [ ] Clean up imports
- [ ] Remove operation-related code from BrowsingEngine

#### 1.3 Enhance Operation Engine
- [ ] Replace placeholder implementations with real gateway operations
- [ ] Fix gateway star projection issues
- [ ] Test core operations (copy, move, delete)

### Phase 2: File System Watcher (Core Infrastructure)
**Timeline: 3-4 days**

#### 2.1 Create FileSystemWatcher
- [ ] Implement Android FileObserver wrapper
- [ ] Create FileSystemEvent types
- [ ] Add event filtering and batching logic
- [ ] Handle permissions and edge cases

#### 2.2 Integrate with BrowsingEngine
- [ ] Add file watching to directory browsing
- [ ] Implement real-time directory updates
- [ ] Add graceful degradation for watch failures
- [ ] Test with large directories and mass changes

#### 2.3 Performance Optimization
- [ ] Lazy watching (only watch viewed directories)
- [ ] Event batching to prevent UI thrashing
- [ ] Memory management for long-lived watchers

### Phase 3: Optimistic Updates (Responsiveness)
**Timeline: 2-3 days**

#### 3.1 Create OperationHint System
- [ ] Define OperationHint types
- [ ] Implement hint emission in OperationEngine
- [ ] Create hint application logic in BrowsingEngine

#### 3.2 Optimistic Update Logic
- [ ] Immediate UI updates from operation hints
- [ ] Reconciliation with file system events
- [ ] Handle hint-reality mismatches

#### 3.3 State Management
- [ ] Optimistic vs confirmed state tracking
- [ ] Conflict resolution between hints and file events
- [ ] Rollback mechanism for incorrect hints

### Phase 4: Event-Based Coordination (Integration)
**Timeline: 2-3 days**

#### 4.1 Event Bus Implementation
- [ ] Create event communication channel
- [ ] Connect OperationEngine hints to BrowsingEngine
- [ ] Implement event ordering and deduplication

#### 4.2 ExplorerWorkspace Integration
- [ ] Update workspace to use new engines
- [ ] Maintain existing public API compatibility
- [ ] Add configuration for file watching

#### 4.3 Error Handling
- [ ] Handle file watcher failures gracefully
- [ ] Fallback to periodic refresh when needed
- [ ] User notification for degraded modes

### Phase 5: Testing and Optimization (Quality)
**Timeline: 2-3 days**

#### 5.1 Comprehensive Testing
- [ ] Unit tests for file watcher
- [ ] Integration tests for optimistic updates
- [ ] Performance tests with large directories
- [ ] Concurrent operation testing

#### 5.2 Edge Case Handling
- [ ] External tool modifications
- [ ] Network filesystem edge cases
- [ ] Permission changes during operations
- [ ] Rapid file system changes

#### 5.3 Performance Tuning
- [ ] Memory usage optimization
- [ ] Battery impact assessment
- [ ] Large directory handling
- [ ] Background/foreground behavior

## Technical Implementation Details

### Android FileObserver Integration
```kotlin
class AndroidFileSystemWatcher : FileSystemWatcher {
    private val observers = mutableMapOf<APath, FileObserver>()
    
    override fun watch(path: APath): Flow<FileSystemEvent> = callbackFlow {
        val observer = object : FileObserver(path.path, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val eventType = when (event and ALL_EVENTS) {
                    CREATE -> FileSystemEvent.FileCreated(APath.build(path))
                    DELETE -> FileSystemEvent.FileDeleted(APath.build(path))
                    MODIFY -> FileSystemEvent.FileModified(APath.build(path))
                    MOVED_FROM, MOVED_TO -> FileSystemEvent.FileRenamed(...)
                    else -> return
                }
                trySend(eventType)
            }
        }
        
        observers[path] = observer
        observer.startWatching()
        
        awaitClose {
            observer.stopWatching()
            observers.remove(path)
        }
    }
}
```

### Directory State Management
```kotlin
data class DirectoryState(
    val path: APath,
    val baseItems: List<ExplorerItem>,  // From file system
    val optimisticItems: List<ExplorerItem>,  // With hints applied
    val version: Long,
    val lastRefresh: Instant,
    val isWatching: Boolean,
    val pendingHints: List<OperationHint>
) {
    val displayItems: List<ExplorerItem>
        get() = if (pendingHints.isNotEmpty()) optimisticItems else baseItems
        
    fun applyHint(hint: OperationHint): DirectoryState {
        // Apply hint to create new optimistic state
    }
    
    fun confirmWithFileSystem(items: List<ExplorerItem>): DirectoryState {
        // File system is source of truth, update base state
    }
}
```

### Event Flow Architecture
```
OperationEngine
    ↓ (OperationHint)
BrowsingEngine ← FileSystemWatcher
    ↓ (DirectoryContent)
ExplorerWorkspace
    ↓ (UI State)
ExplorerWorkspaceViewModel
```

## Risk Analysis and Mitigation

### Risk 1: FileObserver Limitations
**Impact**: High - Core functionality depends on file watching
**Probability**: Medium - Known Android limitations

**Mitigation:**
- Fallback to periodic refresh when FileObserver fails
- Graceful degradation with user notification
- Manual refresh option always available

### Risk 2: Performance with Large Directories
**Impact**: High - Poor UX with thousands of files
**Probability**: Medium - Users may have large directories

**Mitigation:**
- Event batching to prevent UI thrashing
- Lazy loading with virtualized lists
- Configurable watch depth and file count limits

### Risk 3: Battery Impact
**Impact**: Medium - Continuous file watching may drain battery
**Probability**: Low - FileObserver is efficient

**Mitigation:**
- Stop watching when app is backgrounded
- Configurable watching behavior
- Battery usage monitoring and user controls

### Risk 4: Permission Changes
**Impact**: Medium - File watching may stop working
**Probability**: Low - Rare but possible

**Mitigation:**
- Permission monitoring and re-initialization
- Fallback to non-watching mode
- User notification of degraded functionality

### Risk 5: Race Conditions
**Impact**: High - Inconsistent UI state
**Probability**: Medium - Multiple async updates

**Mitigation:**
- Proper event ordering with timestamps
- Atomic state updates
- Comprehensive testing of concurrent scenarios

## Success Metrics

### Functional Metrics
- [ ] All existing operations continue to work
- [ ] External file changes appear in UI within 1 second
- [ ] Optimistic updates appear immediately (<100ms)
- [ ] No cache inconsistencies in normal operation

### Performance Metrics
- [ ] Directory loading time unchanged or improved
- [ ] Memory usage increase <20% for watched directories
- [ ] Battery impact <5% increase in typical usage
- [ ] UI responsiveness maintained during operations

### Reliability Metrics
- [ ] File watcher recovers from failures
- [ ] No crashes from rapid file system changes
- [ ] Graceful degradation when watching unavailable
- [ ] Data consistency maintained under all conditions

## Migration Strategy

### Backward Compatibility
1. **Public API**: ExplorerWorkspace interface remains unchanged
2. **State Types**: All existing state types preserved
3. **Event Types**: Existing events continue to work
4. **Configuration**: New features are opt-in initially

### Rollback Plan
1. **Feature Flags**: File watching can be disabled
2. **Fallback Mode**: System works without file watching
3. **Quick Revert**: Git commits allow easy rollback
4. **Monitoring**: Metrics to detect issues quickly

### Testing Strategy
1. **Unit Tests**: Individual component testing
2. **Integration Tests**: End-to-end operation flows
3. **Performance Tests**: Large directory handling
4. **Device Tests**: Multiple Android versions
5. **User Testing**: Beta testing with real usage patterns

## Timeline Summary

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Cleanup and Rename | 2 days | None |
| Phase 2: File System Watcher | 4 days | Phase 1 |
| Phase 3: Optimistic Updates | 3 days | Phase 2 |
| Phase 4: Event Coordination | 3 days | Phase 3 |
| Phase 5: Testing and Polish | 3 days | Phase 4 |

**Total Estimated Time: 15 days (3 weeks)**

## Next Steps

1. Begin Phase 1 with component cleanup and renaming
2. Implement FileSystemWatcher with basic FileObserver integration
3. Test file watching with simple directory changes
4. Gradually add optimistic updates and coordination
5. Comprehensive testing and optimization

This plan provides a structured approach to modernizing the Explorer architecture while maintaining reliability and performance.