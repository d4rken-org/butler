# Phase 2: Picker Polish & Selection Modes - Implementation Plan

**Status:** Phase 2.1 Architecture Complete - Behavior Implementation Pending
**Last Updated:** 2025-10-15
**Branch:** worktree-searcher

---

## ✅ Phase 2.1 - Selection Architecture (COMPLETED)

### What Was Done

1. **Created `PickerConfig.Selection` sealed class** with 4 modes:
   - `DirectorySingle` - Navigate to folder, select via button
   - `DirectoryMulti` - Long-press folders to toggle selection
   - `FileSingle` - Tap file for instant selection (no confirm)
   - `FileMulti` - Tap files to toggle selection

2. **Moved picker contracts to shared module**:
   - `ExplorerPickerArguments.kt` → `app-workspace/src/main/.../workspace/core/picker/`
   - `PickerConfig.kt` → `app-workspace/src/main/.../workspace/core/picker/`
   - **Removed reflection hack** in `SearcherWorkspaceViewModel` (replaced 30+ lines of reflection with 6 lines of clean code)

3. **Updated all references**:
   - ✅ ExplorerWorkspace.kt
   - ✅ ExplorerPickerTopBar.kt (has `TODO improve title texts` comment on line 38)
   - ✅ ExplorerWorkspacePage.kt
   - ✅ ExplorerWorkspaceViewModel.kt
   - ✅ SearcherWorkspaceViewModel.kt

4. **Added string resources** (`strings.xml`):
   - `explorer_picker_select_directory_title`
   - `explorer_picker_select_directories_title`
   - `explorer_picker_select_directories_count_title`
   - `explorer_picker_select_file_title`
   - `explorer_picker_select_files_title`
   - `explorer_picker_select_files_count_title`

5. **Updated `confirmPickerSelection()`** to handle all 4 modes (lines 1062-1087 in ExplorerWorkspaceViewModel.kt)

6. **Build status**: ✅ All modules compile successfully

---

## 🚧 Phase 2.1 - Selection Behavior (TODO)

### What Needs Implementation

The architecture exists but the **actual selection behaviors** aren't implemented yet:

#### 1. Path Selectability Validation
**Problem:** Virtual paths like "Home" and "Device Storage" should not be selectable in DirectorySingle mode.

**Implementation:**
- Add `isPathSelectable()` function to determine if path is real vs virtual
- Update Select button's `enabled` state based on current path
- Show disabled state with explanatory text if needed

**Files to modify:**
- ExplorerWorkspaceViewModel.kt - add selectability logic
- ExplorerPickerTopBar.kt - conditionally disable button

---

#### 2. FileSingle Instant Selection
**Problem:** In FileSingle mode, tapping a file should instantly select it and close the picker (no confirm button needed).

**Current behavior:** Files are navigated or show options dialog

**Implementation:**
- Detect FileSingle mode in `navigate(item: ExplorerItem)` (line 343)
- When item is `ExplorerItem.File` and mode is FileSingle:
  - Skip file options dialog
  - Immediately emit PickerResult event
  - Close picker workspace
- Hide/disable Select button in this mode (already done in ExplorerPickerTopBar.kt line 68)

**Files to modify:**
- ExplorerWorkspaceViewModel.kt (navigate function)

---

#### 3. FileMulti Toggle Selection
**Problem:** In FileMulti mode, tapping files should toggle their selection (show checkboxes).

**Current behavior:** Tap navigates or shows dialog, long-press enters selection mode

**Implementation:**
- Detect FileMulti mode in item click handlers
- Change click behavior: tap file → toggle selection (don't show dialog)
- Tap folder → navigate (existing behavior)
- Show checkboxes on all files
- Update title bar with count: "Select Files (3)"

**Files to modify:**
- ExplorerWorkspacePage.kt - onClick handler for files (lines 131-137, 206-212)
- ExplorerWorkspaceViewModel.kt - navigate function should check mode
- Item UI components - show checkboxes in FileMulti mode

---

#### 4. DirectoryMulti Toggle Selection
**Problem:** In DirectoryMulti mode, long-pressing folders should toggle their selection.

**Current behavior:** Long-press enters selection mode, works for both files and folders

**Implementation:**
- Similar to FileMulti but for directories
- Tap folder → navigate (existing)
- Long-press folder → toggle selection (existing, but verify it works)
- Show checkboxes on all folders
- Update title bar with count: "Select Folders (2)"

**Files to modify:**
- Verify existing selection mode works correctly for DirectoryMulti
- May need to filter selectableItems to only directories

---

#### 5. Visual Feedback
**Problem:** No visual distinction between selection modes.

**Implementation:**
- Show checkboxes based on mode:
  - DirectorySingle: No checkboxes
  - DirectoryMulti: Checkboxes on directories
  - FileSingle: No checkboxes
  - FileMulti: Checkboxes on files
- Selection highlights/ripple effects
- Disabled state styling for non-selectable items

**Files to modify:**
- LookupItemRow.kt / LookupItemGrid.kt - conditional checkbox rendering
- Pass selection mode down from ViewModel state

---

## 📋 Phase 2.2 - Title Bar Redesign (TODO)

### Requirements

1. **Two-row layout:**
   - Row 1: Breadcrumbs
   - Row 2: Cancel button (left) + Select button (right)

2. **Add icons:**
   - Cancel: X icon
   - Select: Check icon

3. **Better visual hierarchy:**
   - Breadcrumbs more prominent
   - Action buttons clearly separated

4. **Fix title texts** (currently has `TODO improve title texts` on line 38 of ExplorerPickerTopBar.kt)

### Files to Modify
- ExplorerPickerTopBar.kt - redesign layout structure
- strings.xml - potentially improve text descriptions

---

## 📋 Phase 2.3 - Animations (TODO)

### Requirements

1. **Modal enter/exit animations:**
   - Slide up + fade in when opening
   - Slide down + fade out when closing
   - Material 3 motion specs (300-400ms duration)

2. **Selection feedback:**
   - Ripple effect on tap
   - Scale animation when checkbox toggles
   - Smooth transitions

### Files to Modify
- WorkspaceModalDialog.kt - add AnimatedVisibility with slide/fade
- Item UI components - add selection animations

---

## 📋 Phase 2.4 - Tablet Optimization (TODO)

### Requirements

1. **Adaptive layout based on screen width:**
   - Phone (<600dp): Full-screen modal (current behavior)
   - Tablet (≥600dp): Bottom sheet modal

2. **Better space utilization:**
   - Bottom sheet on tablets looks better than full-screen
   - Easier to see context (what's behind the picker)

### Implementation Approach
- Use `BoxWithConstraints` or `WindowSizeClass` to detect screen size
- Conditionally use `ModalBottomSheet` vs `Dialog`
- Maintain same content for both

### Files to Modify
- WorkspaceModalDialog.kt - adaptive layout logic
- May need to adjust max height constraints

---

## 🎯 Recommended Next Session Plan

1. **Start with FileSingle instant selection** (highest impact, clearest UX)
2. **Then FileMulti toggle selection** (similar pattern)
3. **Add visual feedback** (checkboxes, highlights)
4. **Implement path selectability validation**
5. **Verify DirectoryMulti works correctly**
6. **Test all 4 modes thoroughly**

Then move to Phase 2.2 (title bar redesign) for visual polish.

---

## 📝 Notes

- All architectural changes are done - no more module structure changes needed
- The reflection hack is eliminated - clean imports everywhere
- Build is passing - safe foundation to build on
- Focus now is on **behavior implementation** and **UX polish**
