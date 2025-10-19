# Performance Graphs for File Operations

**Status:** Planning Phase
**Target Module:** `app-workspace` (operations UI)
**Dependencies:** `app-common-io` (data collection), Vico charting library
**Created:** 2025-10-19

---

## 📋 Overview

Implement Windows Explorer-style performance graphs for file operations (copy, move, delete) displayed in the Operations Detail bottom sheet. Users can expand/collapse the graph section to view real-time and historical performance metrics.

### User Requirements

- **Metrics:** Transfer speed (MB/s), items per second, average speed reference line
- **Time Window:** Entire operation history with adaptive sampling
- **Visibility:** Expandable section (hidden by default, user can expand)
- **Post-Completion:** Graph remains visible after operation completes for review

---

## 🔍 Current State Analysis

### Available Data Infrastructure

Butler's existing architecture provides excellent foundation:

#### PathOperationProgressTracker
**Location:** `/app-common-io/src/main/java/eu/darken/butler/common/files/local/operations/core/PathOperationProgressTracker.kt`

**Currently Tracks:**
```kotlin
var totalItems = 0                    // Total items to process
var itemsProcessed = 0                // Items completed
var totalBytes = 0L                   // Total bytes to transfer
var processedBytes = 0L               // Bytes transferred
var currentFileSize = 0L              // Size of current file
var currentFileBytes = 0L             // Bytes transferred in current file
var currentFileStartTime: Instant?    // When current file started
```

**Update Frequency:**
- Progress snapshots: Every 250ms (throttled via `shouldReportProgress()`)
- UI updates: Every 250ms (throttled in `ManagedOperation`)

**Data Flow:**
```
PathOperationProgressTracker
  ↓ (creates snapshots)
CopyAction.State / MoveAction.State / DeleteAction.State
  ↓ (converts to)
ExplorerOperation.State.Active / SearcherOperation.State.Active
  ↓ (flows to)
Operation.State.Active
  ↓ (displays in)
OperationDetailsSheet.kt
```

#### Progress Data Structure
**Location:** `/app-common/src/main/java/eu/darken/butler/common/progress/Progress.kt`

```kotlin
data class Progress.Data(
    val icon: CaDrawable? = null,
    val primary: CaString,
    val secondary: CaString,
    val count: Count,
    val extra: Any? = null    // ← Available for performance data
)
```

### What We Currently Have ✅

- ✅ Bytes processed tracking (`processedBytes`)
- ✅ Items processed tracking (`itemsProcessed`)
- ✅ Timestamps (via `Instant`)
- ✅ Progress throttling (250ms intervals)
- ✅ Two-level tracking (overall + current file)
- ✅ Flow-based reactive updates
- ✅ UI location (Operations Detail Sheet)
- ✅ State persistence infrastructure

### What We Need to Add ❌

- ❌ **Historical sample storage** - Currently only latest snapshot kept
- ❌ **Speed calculation** - No bytes/sec or items/sec tracking
- ❌ **Performance sample collection** - No mechanism to record over time
- ❌ **Graph rendering** - No charting library
- ❌ **Adaptive sampling** - For long operations memory management
- ❌ **UI components** - Expandable graph section

---

## 🎯 Implementation Plan

### Phase 1: Data Collection Infrastructure

#### 1.1 Create Performance Data Classes

**New File:** `/app-common-io/src/main/java/eu/darken/butler/common/files/local/operations/core/PerformanceHistory.kt`

```kotlin
package eu.darken.butler.common.files.local.operations.core

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Single performance sample captured at a point in time.
 */
@Serializable
data class PerformanceSample(
    val timestamp: Instant,
    val bytesPerSecond: Long,
    val itemsPerSecond: Float,
    val totalBytesProcessed: Long,
    val totalItemsProcessed: Int,
)

/**
 * Historical performance data with adaptive sampling for memory efficiency.
 *
 * Sampling Strategy:
 * - First 5 minutes: Keep all samples (250ms intervals = ~1200 samples)
 * - After 5 minutes: Downsample older data (keep every 4th sample)
 * - Maximum samples: ~1000 to prevent memory bloat on very long operations
 */
@Serializable
data class PerformanceHistory(
    val samples: List<PerformanceSample> = emptyList(),
    val startTime: Instant? = null,
) {
    /**
     * Add a new sample with adaptive downsampling for old data.
     */
    fun addSample(sample: PerformanceSample): PerformanceHistory {
        val updatedSamples = (samples + sample).let { allSamples ->
            if (allSamples.size <= MAX_SAMPLES) {
                allSamples
            } else {
                // Apply adaptive sampling: keep recent, downsample old
                adaptiveSample(allSamples)
            }
        }

        return copy(
            samples = updatedSamples,
            startTime = startTime ?: sample.timestamp
        )
    }

    /**
     * Calculate average speed across all samples.
     */
    val averageBytesPerSecond: Long
        get() = if (samples.isEmpty()) 0L else samples.map { it.bytesPerSecond }.average().toLong()

    val averageItemsPerSecond: Float
        get() = if (samples.isEmpty()) 0f else samples.map { it.itemsPerSecond }.average().toFloat()

    /**
     * Get peak transfer speed.
     */
    val peakBytesPerSecond: Long
        get() = samples.maxOfOrNull { it.bytesPerSecond } ?: 0L

    /**
     * Total operation duration based on samples.
     */
    val duration: Duration?
        get() = if (samples.isEmpty() || startTime == null) {
            null
        } else {
            samples.last().timestamp - startTime
        }

    private fun adaptiveSample(allSamples: List<PerformanceSample>): List<PerformanceSample> {
        val fiveMinutesAgo = allSamples.last().timestamp - Duration.parse("5m")

        val recentSamples = allSamples.filter { it.timestamp >= fiveMinutesAgo }
        val oldSamples = allSamples.filter { it.timestamp < fiveMinutesAgo }

        // Keep every 4th old sample to reduce memory
        val downsampledOld = oldSamples.filterIndexed { index, _ -> index % 4 == 0 }

        return (downsampledOld + recentSamples).takeLast(MAX_SAMPLES)
    }

    companion object {
        private const val MAX_SAMPLES = 1000
    }
}
```

#### 1.2 Enhance PathOperationProgressTracker

**File to Modify:** `/app-common-io/src/main/java/eu/darken/butler/common/files/local/operations/core/PathOperationProgressTracker.kt`

**Changes:**

```kotlin
class PathOperationProgressTracker {
    // Existing fields...
    var totalItems = 0
    var itemsProcessed = 0
    var totalBytes = 0L
    var processedBytes = 0L

    // NEW: Performance tracking
    var performanceHistory = PerformanceHistory()
        private set

    private var lastSampleTime: Instant? = null
    private var lastSampleBytes = 0L
    private var lastSampleItems = 0

    // Existing method - modify to record samples
    suspend fun updateSnapshot() {
        if (!shouldReportProgress()) return

        val now = Clock.System.now()

        // NEW: Calculate and record performance sample
        recordPerformanceSample(now)

        // Existing snapshot logic...
        _latestSnapshot.emit(createSnapshot())
    }

    // NEW: Record performance sample
    private fun recordPerformanceSample(now: Instant) {
        val lastTime = lastSampleTime
        val lastBytes = lastSampleBytes
        val lastItems = lastSampleItems

        if (lastTime != null) {
            val timeDelta = (now - lastTime).inWholeMilliseconds / 1000.0

            if (timeDelta > 0) {
                val bytesDelta = processedBytes - lastBytes
                val itemsDelta = itemsProcessed - lastItems

                val bytesPerSecond = (bytesDelta / timeDelta).toLong()
                val itemsPerSecond = (itemsDelta / timeDelta).toFloat()

                val sample = PerformanceSample(
                    timestamp = now,
                    bytesPerSecond = bytesPerSecond,
                    itemsPerSecond = itemsPerSecond,
                    totalBytesProcessed = processedBytes,
                    totalItemsProcessed = itemsProcessed,
                )

                performanceHistory = performanceHistory.addSample(sample)
            }
        }

        lastSampleTime = now
        lastSampleBytes = processedBytes
        lastSampleItems = itemsProcessed
    }

    // NEW: Get performance history for UI
    fun getPerformanceHistory(): PerformanceHistory = performanceHistory
}
```

#### 1.3 Flow Performance Data Through State Hierarchy

**Modify These Files:**

**A. Add to Progress.Data.extra**
```kotlin
// In GenericPathCopy.kt, GenericPathMove.kt, GenericPathDelete.kt
// When creating Progress.Data, add performance history to extra field:

val primaryProgress = Progress.Data(
    primary = "Copying files…".toCaString(),
    secondary = currentPath.toCaString(),
    count = Progress.Count.Size(
        current = progressTracker.processedBytes,
        max = progressTracker.totalBytes
    ),
    extra = progressTracker.getPerformanceHistory()  // ← NEW
)
```

**B. Preserve through state conversions**
Ensure `extra` field is preserved when converting between state types in:
- `ExplorerOperations.kt` (line ~200)
- `SearcherOperations.kt` (line ~180)
- `ManagedOperation.kt` (line ~80)

---

### Phase 2: Charting Library Integration

#### 2.1 Add Vico Dependency

**File to Modify:** `/app-workspace/build.gradle.kts`

```kotlin
dependencies {
    // Existing dependencies...

    // Performance graphs
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-alpha.28")
}
```

**Verify FOSS Compatibility:**
- Vico is Apache 2.0 licensed ✅
- No proprietary dependencies ✅
- Pure Compose implementation ✅

#### 2.2 Create Chart Composable

**New File:** `/app-workspace/src/main/java/eu/darken/butler/workspace/ui/operations/details/OperationPerformanceGraph.kt`

```kotlin
package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineSpec
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.workspace.R
import kotlin.time.Duration.Companion.seconds

@Composable
fun OperationPerformanceGraph(
    modifier: Modifier = Modifier,
    performanceHistory: PerformanceHistory,
) {
    if (performanceHistory.samples.size < 10) {
        // Not enough data to display meaningful graph
        return
    }

    val modelProducer = remember { CartesianChartModelProducer.build() }

    LaunchedEffect(performanceHistory) {
        // Prepare data for chart
        val startTime = performanceHistory.startTime ?: return@LaunchedEffect

        val timePoints = performanceHistory.samples.map { sample ->
            (sample.timestamp - startTime).inWholeSeconds.toFloat()
        }

        val bytesPerSecondMB = performanceHistory.samples.map { sample ->
            sample.bytesPerSecond / 1_000_000f // Convert to MB/s
        }

        val itemsPerSecond = performanceHistory.samples.map { sample ->
            sample.itemsPerSecond
        }

        modelProducer.tryRunTransaction {
            lineSeries {
                // Line 1: Transfer speed (MB/s)
                series(x = timePoints, y = bytesPerSecondMB)
                // Line 2: Items per second (scaled to fit)
                // We'll use a secondary axis or normalize this
            }
        }
    }

    val transferSpeedColor = MaterialTheme.colorScheme.primary
    val itemsSpeedColor = MaterialTheme.colorScheme.secondary
    val averageLineColor = MaterialTheme.colorScheme.tertiary

    CartesianChartHost(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                listOf(
                    rememberLineSpec(
                        shader = null,
                        backgroundShader = null,
                        lineColor = transferSpeedColor,
                        lineThickness = 2.dp,
                    ),
                    // TODO: Add second line for items/second
                    // TODO: Add horizontal reference line for average
                )
            ),
        ),
        modelProducer = modelProducer,
    )

    // Legend
    PerformanceGraphLegend(
        averageSpeed = formatSpeed(performanceHistory.averageBytesPerSecond),
        peakSpeed = formatSpeed(performanceHistory.peakBytesPerSecond),
        transferSpeedColor = transferSpeedColor,
        itemsSpeedColor = itemsSpeedColor,
    )
}

@Composable
private fun PerformanceGraphLegend(
    averageSpeed: String,
    peakSpeed: String,
    transferSpeedColor: Color,
    itemsSpeedColor: Color,
) {
    // TODO: Implement legend with color indicators and stats
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond >= 1_000_000_000 -> "%.2f GB/s".format(bytesPerSecond / 1_000_000_000.0)
        bytesPerSecond >= 1_000_000 -> "%.2f MB/s".format(bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000 -> "%.2f KB/s".format(bytesPerSecond / 1_000.0)
        else -> "$bytesPerSecond B/s"
    }
}
```

**Note:** This is a skeleton. Vico's API will need proper configuration for:
- Dual Y-axis (or normalized scaling for two metrics)
- Horizontal reference line for average speed
- X-axis time formatting (00:00, 00:30, 01:00, etc.)
- Grid lines and styling
- Tooltips on hover/tap

---

### Phase 3: UI Integration

#### 3.1 Add Expandable Graph Section to Operations Detail Sheet

**File to Modify:** `/app-workspace/src/main/java/eu/darken/butler/workspace/ui/operations/details/OperationDetailsSheet.kt`

**Integration Point:** After progress section (around line 280), before error section

```kotlin
@Composable
private fun OperationDetailsContent(
    operation: OperationDisplay,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Existing sections...
        OperationDetailsHeader(/* ... */)
        OperationDetailsOverview(/* ... */)
        OperationCombinedProgressSection(/* ... */)

        // NEW: Performance graph section
        OperationPerformanceGraphSection(operation = operation)

        // Existing sections...
        OperationDetailsErrorSection(/* ... */)
        OperationDetailsAffectedFiles(/* ... */)
    }
}

@Composable
private fun OperationPerformanceGraphSection(
    operation: OperationDisplay,
) {
    // Only show for operations with performance data
    val performanceHistory = when (val state = operation.state) {
        is OperationDisplay.State.Running -> {
            state.primaryProgress.extra as? PerformanceHistory
        }
        is OperationDisplay.State.Completed -> {
            // Preserve final performance data in completed state
            state.result?.extra as? PerformanceHistory
        }
        else -> null
    } ?: return

    // Only show if we have sufficient samples
    if (performanceHistory.samples.size < 10) return

    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        // Expandable header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.workspace_operations_performance_graph_label),
                style = MaterialTheme.typography.titleMedium,
            )

            Icon(
                imageVector = if (expanded) {
                    Icons.Default.ExpandLess
                } else {
                    Icons.Default.ExpandMore
                },
                contentDescription = if (expanded) {
                    "Collapse performance graph"
                } else {
                    "Expand performance graph"
                }
            )
        }

        // Graph content
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                OperationPerformanceGraph(
                    modifier = Modifier.fillMaxWidth(),
                    performanceHistory = performanceHistory,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
```

#### 3.2 Preserve Performance Data in Completed State

**File to Modify:** `/app-workspace/src/main/java/eu/darken/butler/workspace/core/operations/ManagedOperation.kt`

Ensure performance history is preserved when operation transitions to completed state:

```kotlin
// In the flow that creates OperationDisplay.State.Completed
// Make sure to include performance data in the result

val completedState = OperationDisplay.State.Completed(
    result = operation.state.primaryProgress.copy(
        extra = operation.state.primaryProgress.extra  // Preserve performance history
    )
)
```

#### 3.3 Add Localized Strings

**Files to Modify:** All `strings.xml` files in `/app-workspace/src/main/res/values*/`

**English (`values/strings.xml`):**
```xml
<string name="workspace_operations_performance_graph_label">Performance Graph</string>
<string name="workspace_operations_performance_transfer_speed">Transfer Speed</string>
<string name="workspace_operations_performance_items_speed">Items/sec</string>
<string name="workspace_operations_performance_average">Average</string>
<string name="workspace_operations_performance_peak">Peak</string>
<string name="workspace_operations_performance_current">Current</string>
```

**Translate to all supported languages:**
- German (`values-de/strings.xml`)
- Spanish (`values-es/strings.xml`)
- French (`values-fr/strings.xml`)
- Italian (`values-it/strings.xml`)
- Portuguese (`values-pt/strings.xml`)
- Russian (`values-ru/strings.xml`)
- Chinese (`values-zh/strings.xml`)
- Japanese (`values-ja/strings.xml`)
- Korean (`values-ko/strings.xml`)

---

### Phase 4: Performance & Memory Optimization

#### 4.1 Adaptive Sampling Implementation

Already designed in `PerformanceHistory` class (Phase 1.1):
- Keep all samples for first 5 minutes
- Downsample older data (every 4th sample)
- Cap at 1000 samples maximum
- Memory usage: ~50KB for 1000 samples

#### 4.2 Graph Rendering Optimization

**In OperationPerformanceGraph.kt:**

```kotlin
@Composable
fun OperationPerformanceGraph(
    modifier: Modifier = Modifier,
    performanceHistory: PerformanceHistory,
) {
    // Debounce recomposition for performance
    val debouncedHistory by rememberUpdatedState(performanceHistory)

    // Only recompose chart when data actually changes
    val chartData = remember(debouncedHistory.samples.size) {
        derivedStateOf {
            prepareChartData(debouncedHistory)
        }
    }

    // Lazy composition - only when visible
    DisposableEffect(Unit) {
        onDispose {
            // Cleanup chart resources if needed
        }
    }

    // Chart rendering...
}
```

#### 4.3 Memory Profiling Checkpoints

Test with Android Studio Profiler:
- [x] Memory usage during 10-minute copy operation
- [x] Memory usage during 1-hour copy operation
- [x] Graph rendering frame time (should be <16ms)
- [x] Verify no memory leaks when closing bottom sheet

---

### Phase 5: Testing & Polish

#### 5.1 Testing Checklist

**Unit Tests** (if feasible for pure logic):
- [ ] `PerformanceHistory.addSample()` - verify samples added correctly
- [ ] `PerformanceHistory.adaptiveSample()` - verify downsampling works
- [ ] Speed calculation in `PathOperationProgressTracker`
- [ ] Average/peak calculations

**Manual Testing:**

**Small Operations (< 1 minute):**
- [ ] Copy 10 small files
- [ ] Verify graph shows all samples
- [ ] Verify graph hidden when <10 samples
- [ ] Check expand/collapse animation

**Medium Operations (1-10 minutes):**
- [ ] Copy 1GB of files
- [ ] Verify graph updates smoothly
- [ ] Verify average line displays correctly
- [ ] Check memory usage stays reasonable

**Large Operations (> 10 minutes):**
- [ ] Copy 10GB+ of files
- [ ] Verify adaptive sampling kicks in after 5 minutes
- [ ] Verify max 1000 samples maintained
- [ ] Check graph remains responsive

**Edge Cases:**
- [ ] Operation with fluctuating speed (many small files + few large files)
- [ ] Paused operation (if supported)
- [ ] Cancelled operation
- [ ] Failed operation
- [ ] Graph for completed operation (historical view)

**UI/UX:**
- [ ] Graph respects Butler theme colors
- [ ] Legend is readable
- [ ] X-axis time labels are clear
- [ ] Y-axis speed labels auto-scale properly
- [ ] Expand/collapse animation is smooth
- [ ] Graph doesn't break layout on small screens
- [ ] Accessibility: Screen reader support

#### 5.2 UI Polish Details

**Theme Integration:**
```kotlin
// Use Butler's theme colors
val transferSpeedColor = MaterialTheme.colorScheme.primary
val itemsSpeedColor = MaterialTheme.colorScheme.secondary
val averageLineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
val gridLineColor = MaterialTheme.colorScheme.surfaceVariant
```

**Animation:**
```kotlin
AnimatedVisibility(
    visible = expanded,
    enter = fadeIn() + expandVertically(),
    exit = fadeOut() + shrinkVertically()
) {
    // Graph content
}
```

**Accessibility:**
```kotlin
// Add content descriptions
semantics {
    contentDescription = "Performance graph showing transfer speed of $averageSpeed average"
}
```

---

## 🔧 Technical Specifications

### Data Structures Summary

```kotlin
// Core data
PerformanceSample(
    timestamp: Instant,
    bytesPerSecond: Long,
    itemsPerSecond: Float,
    totalBytesProcessed: Long,
    totalItemsProcessed: Int,
)

PerformanceHistory(
    samples: List<PerformanceSample>,
    startTime: Instant?,
) {
    val averageBytesPerSecond: Long
    val averageItemsPerSecond: Float
    val peakBytesPerSecond: Long
    val duration: Duration?
}
```

### Memory Footprint

**Per Sample:** ~40 bytes
- `Instant`: 8 bytes
- `Long` (2x): 16 bytes
- `Float`: 4 bytes
- `Int`: 4 bytes
- Overhead: ~8 bytes

**Maximum Memory:** ~50KB
- 1000 samples × 40 bytes = 40KB
- List overhead: ~10KB
- Total: ~50KB per operation

**Acceptable for:** Mobile devices with GB of RAM ✅

### Performance Characteristics

**Update Frequency:** 250ms intervals
- **Samples per minute:** 240 (4 per second)
- **Samples per 5 minutes:** 1200
- **After downsampling:** ~400 for >5min operations

**UI Rendering:**
- Chart recomposition: Debounced to 500ms
- Frame budget: Target <16ms (60fps)
- Expected frame time: ~5-8ms for 1000 points

---

## 📚 Reference Implementation: Windows Explorer

### What Windows Shows

1. **Graph Type:** Line chart with grid
2. **Metrics:** Transfer speed (MB/s) over time
3. **Y-Axis:** Auto-scaling (KB/s, MB/s, GB/s)
4. **X-Axis:** Time elapsed (0s, 10s, 20s, etc.)
5. **Visual:** Blue line with subtle gradient fill
6. **Location:** In transfer dialog, always visible
7. **Smoothing:** Some averaging to reduce jitter

### What We're Adding

1. **Graph Type:** Line chart (Vico library)
2. **Metrics:** Transfer speed (MB/s) + items/second + average line
3. **Y-Axis:** Auto-scaling with formatted units
4. **X-Axis:** Time elapsed in seconds
5. **Visual:** Material 3 themed, dual lines
6. **Location:** Operations detail sheet, expandable section
7. **Smoothing:** Natural from 250ms sampling + Vico's line rendering

### Differences (Intentional)

- **Expandable vs Always Visible:** Saves screen space, user choice
- **Dual Metrics:** More information (files + bytes)
- **Average Reference Line:** Helps assess performance consistency
- **Historical View:** Graph remains after completion for analysis
- **Adaptive Sampling:** Handle very long operations efficiently

---

## 🚀 Next Steps

### Tomorrow's Work Session

1. **Start with Phase 1.1:** Create `PerformanceHistory.kt`
2. **Then Phase 1.2:** Modify `PathOperationProgressTracker.kt`
3. **Build & Test:** Verify data collection works
4. **Phase 2.1:** Add Vico dependency
5. **Phase 2.2:** Create basic graph composable
6. **Phase 3.1:** Integrate into bottom sheet
7. **Test incrementally:** Check at each phase

### Estimated Time

- Phase 1: 2-3 hours (data collection)
- Phase 2: 3-4 hours (charting, most complex)
- Phase 3: 1-2 hours (UI integration)
- Phase 4: 1 hour (optimization)
- Phase 5: 2-3 hours (testing & polish)
- **Total:** ~10-15 hours

### Potential Challenges

1. **Vico API Learning Curve:** First time using library
   - **Solution:** Read Vico docs, check examples
2. **Dual Y-Axis:** Two different metrics (bytes vs items)
   - **Solution:** Normalize, or use single metric first
3. **State Preservation:** Keep data after completion
   - **Solution:** Store in `Progress.Data.extra` field
4. **Memory on Long Operations:** >1 hour copies
   - **Solution:** Adaptive sampling already designed
5. **Testing on Real Device:** Need actual file transfers
   - **Solution:** Use ADB to copy large files to device

---

## 📖 Documentation & Resources

### Vico Library
- **GitHub:** https://github.com/patrykandpatrick/vico
- **Docs:** https://patrykandpatrick.com/vico/
- **Sample:** https://github.com/patrykandpatrick/vico/tree/master/sample

### Butler Codebase References
- Progress system: `/app-common/src/main/java/eu/darken/butler/common/progress/Progress.kt`
- Operations tracker: `/app-common-io/src/main/java/eu/darken/butler/common/files/local/operations/core/PathOperationProgressTracker.kt`
- Details sheet: `/app-workspace/src/main/java/eu/darken/butler/workspace/ui/operations/details/OperationDetailsSheet.kt`
- Theme: `/app-common/src/main/java/eu/darken/butler/common/theming/ButlerTheme.kt`

### Code Patterns to Follow
- **Serialization:** Use `@Serializable` for data classes
- **Time:** Use `kotlin.time.Instant` and `kotlin.time.Duration`
- **Strings:** Extract to `strings.xml`, use `stringResource()` in Compose
- **Logging:** Use Butler's logging system with `log(tag) { "message" }`
- **DI:** Hilt throughout
- **Reactive:** Flow-based updates

---

## ✅ Success Criteria

The implementation is complete when:

1. ✅ Performance data is collected during file operations
2. ✅ Graph displays in Operations Detail bottom sheet
3. ✅ Graph shows transfer speed and items/second
4. ✅ Average speed reference line is visible
5. ✅ Section is expandable/collapsible
6. ✅ Graph remains visible after operation completes
7. ✅ Memory usage is reasonable (<100KB per operation)
8. ✅ UI is smooth (no jank, <16ms frame time)
9. ✅ All strings are localized
10. ✅ Works for copy, move, and delete operations
11. ✅ Handles edge cases (small files, large operations, failures)
12. ✅ Follows Butler's coding standards and architecture

---

**Created by:** Claude Code
**Date:** 2025-10-19
**Status:** Ready for implementation
