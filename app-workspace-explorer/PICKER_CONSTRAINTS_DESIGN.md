# Picker Constraints Design Research

## Problem Statement

The current `computeDisabledItems()` and `selectableItems` logic in
`ExplorerWorkspaceViewModel` duplicates picker mode constraints. These constraints determine which items are valid selection targets based on the picker mode (directory picker, file picker, etc.).

**Current location:** `ExplorerWorkspaceViewModel.kt` lines 401-425, 250-282

**Issues:**

1. Logic duplicated in multiple places
2. Constraints are hardcoded in `when` blocks
3. Not extensible for future requirements

## Future Requirements to Consider

- Only empty directories
- Only files of certain MIME types (e.g., "image/*", "application/pdf")
- Only files under a certain size
- Only writable locations
- Combinations: "images under 10MB"

## Architectural Constraint

**Cannot add `ExplorerItem` reference to `PickerConfig`** - would create circular dependency:

- `app-workspace` cannot depend on `app-workspace-explorer`
- `app-workspace-explorer` already depends on `app-workspace`

## Design Options

### Option A: Constraint DSL (Most Extensible)

Define composable constraints in `app-workspace`:

```kotlin
// In app-workspace/src/.../picker/PickerConstraint.kt
sealed interface PickerConstraint : Parcelable {
    @Parcelize data object IsDirectory : PickerConstraint
    @Parcelize data object IsFile : PickerConstraint
    @Parcelize data object IsStorage : PickerConstraint
    @Parcelize data object IsEmpty : PickerConstraint
    @Parcelize data class HasMimeType(val pattern: String) : PickerConstraint  // "image/*"
    @Parcelize data class MaxSize(val bytes: Long) : PickerConstraint
    @Parcelize data class MinSize(val bytes: Long) : PickerConstraint
    @Parcelize data class And(val constraints: List<PickerConstraint>) : PickerConstraint
    @Parcelize data class Or(val constraints: List<PickerConstraint>) : PickerConstraint
    @Parcelize data class Not(val constraint: PickerConstraint) : PickerConstraint
}

// Selection composes constraints
sealed class Selection : Parcelable {
    abstract val targetConstraint: PickerConstraint

    @Parcelize data object DirectorySingle : Selection() {
        override val targetConstraint = Or(listOf(IsDirectory, IsStorage))
    }

    @Parcelize data object FileMulti : Selection() {
        override val targetConstraint = IsFile
    }

    // Future example: image picker with size limit
    @Parcelize data class ImagePicker(val maxSizeBytes: Long? = null) : Selection() {
        override val targetConstraint = And(
            listOfNotNull(
            IsFile,
            HasMimeType("image/*"),
            maxSizeBytes?.let { MaxSize(it) }
        ))
    }
}
```

Evaluator in explorer module:

```kotlin
// In app-workspace-explorer/src/.../picker/PickerConstraintEvaluator.kt
fun PickerConstraint.matches(item: ExplorerItem): Boolean = when (this) {
    is PickerConstraint.IsDirectory -> item is ExplorerItem.Directory
    is PickerConstraint.IsFile -> item is ExplorerItem.File
    is PickerConstraint.IsStorage -> item is ExplorerItem.Storage
    is PickerConstraint.IsEmpty -> (item as? ExplorerItem.Directory)?.childCount == 0
    is PickerConstraint.HasMimeType -> {
        val file = item as? ExplorerItem.File ?: return false
        file.mimeType.rawType.matches(Regex(pattern.replace("*", ".*")))
    }
    is PickerConstraint.MaxSize -> {
        val lookup = item as? ExplorerItem.Lookup ?: return false
        lookup.lookup.size?.let { it <= bytes } ?: true
    }
    is PickerConstraint.MinSize -> {
        val lookup = item as? ExplorerItem.Lookup ?: return false
        lookup.lookup.size?.let { it >= bytes } ?: false
    }
    is PickerConstraint.And -> constraints.all { it.matches(item) }
    is PickerConstraint.Or -> constraints.any { it.matches(item) }
    is PickerConstraint.Not -> !constraint.matches(item)
}

fun PickerConfig.Selection.isValidTarget(item: ExplorerItem): Boolean =
    targetConstraint.matches(item)
```

**Pros:**

- Highly composable (And/Or/Not)
- New constraints = new sealed subtype
- Complex requirements expressible: `And(IsFile, Or(HasMimeType("image/*"), HasMimeType("video/*")), MaxSize(10MB))`

**Cons:**

- More complex upfront
- Parcelization of nested structures

### Option B: Spec Data Class (Simpler)

```kotlin
// In app-workspace/src/.../picker/PickerTargetSpec.kt
@Parcelize
data class PickerTargetSpec(
    val acceptsDirectories: Boolean = false,
    val acceptsFiles: Boolean = false,
    val acceptsStorage: Boolean = false,
    val mimeTypePatterns: List<String>? = null,  // null = any, ["image/*"] = images only
    val maxSizeBytes: Long? = null,
    val minSizeBytes: Long? = null,
    val requireEmpty: Boolean = false,
) : Parcelable

sealed class Selection : Parcelable {
    abstract val targetSpec: PickerTargetSpec

    @Parcelize data object DirectorySingle : Selection() {
        override val targetSpec = PickerTargetSpec(
            acceptsDirectories = true,
            acceptsStorage = true
        )
    }

    @Parcelize data object FileMulti : Selection() {
        override val targetSpec = PickerTargetSpec(acceptsFiles = true)
    }
}
```

Evaluator:

```kotlin
fun PickerTargetSpec.matches(item: ExplorerItem): Boolean {
    // Type check
    val typeMatches = when (item) {
        is ExplorerItem.Directory -> acceptsDirectories
        is ExplorerItem.File -> acceptsFiles
        is ExplorerItem.Storage -> acceptsStorage
        else -> false
    }
    if (!typeMatches) return false

    // MIME check
    if (mimeTypePatterns != null && item is ExplorerItem.File) {
        val matches = mimeTypePatterns.any { pattern ->
            item.mimeType.rawType.matches(Regex(pattern.replace("*", ".*")))
        }
        if (!matches) return false
    }

    // Size checks...
    // Empty check...

    return true
}
```

**Pros:**

- Simpler to understand
- Flat structure, easy serialization
- Good enough for most use cases

**Cons:**

- Limited composability (no Or between different specs)
- Adding constraints = adding fields (can grow unwieldy)

### Option C: Hybrid (Recommended)

Start with Option B (simpler), but design it so Option A can be added later without breaking changes:

```kotlin
sealed class Selection : Parcelable {
    // Current: simple spec-based
    open val targetSpec: PickerTargetSpec? get() = null

    // Future: constraint-based (when needed)
    open val targetConstraint: PickerConstraint? get() = null

    // Evaluator checks constraint first, falls back to spec
}
```

## Trade-off Summary

| Aspect                  | Constraint DSL    | Spec Data Class |
|-------------------------|-------------------|-----------------|
| Complexity              | Higher            | Lower           |
| Composability           | Full (And/Or/Not) | Limited         |
| "PNG or JPEG under 5MB" | Easy              | Awkward         |
| Adding constraints      | New sealed type   | New field       |
| Serialization           | Complex nesting   | Simple flat     |

## Recommendation

**Start with Option B (Spec)
** for current needs, with a clear migration path to Option A if complex constraints become necessary.

## Files to Modify (When Implemented)

1. `app-workspace/src/.../picker/PickerConfig.kt` - Add `PickerTargetSpec` or `PickerConstraint`
2. `app-workspace-explorer/src/.../picker/PickerConstraintEvaluator.kt` - New file for matching logic
3. `app-workspace-explorer/src/.../ui/explorer/ExplorerWorkspaceViewModel.kt` - Simplify `computeDisabledItems` and
   `selectableItems`

## Related Code Locations

- Current disabled items logic: `ExplorerWorkspaceViewModel.kt:401-425`
- Current selectable items logic: `ExplorerWorkspaceViewModel.kt:250-282`
- PickerConfig.Selection: `app-workspace/src/.../picker/PickerConfig.kt`
- ExplorerItem hierarchy: `app-workspace-explorer/src/.../engine/ExplorerItem.kt`
