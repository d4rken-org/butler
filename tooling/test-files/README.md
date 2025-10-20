# Test File Structure Creator

A portable shell script for creating comprehensive test file structures on Android devices for testing Butler's file operations, navigation, and performance.

## Overview

This script creates three distinct test directory structures:

1. **adirwithlargefiles/** - Large file I/O testing
    - 8 files with random data: 100MB, 200MB, 400MB, 800MB, 1GB, 2GB, 4GB, 8GB
    - Total: ~16.5GB
    - Tests: Large file handling, copy/move operations, disk I/O performance

2. **adirwithmanyfiles/** - Many files handling
    - 4,000 files with random sizes (0-50KB each)
    - Total: ~100MB
    - Tests: Directory listing performance, bulk operations, UI responsiveness

3. **adirwithnesteddata/** - Deep navigation testing
    - 5 main branches with balanced tree structure (10 levels deep)
    - ~1,500 folders with 2-3 child folders each
    - ~3,500 small files (1-10KB) distributed throughout
    - Tests: Deep path navigation, recursive operations, breadcrumb UI

## Requirements

- **Storage**: Minimum 18GB free space on target device
- **Runtime**: Approximately 15-25 minutes (varies by device I/O speed)
- **Commands**: `dd`, `mkdir`, `rm`, `df`, `od` (standard on Android)
- **Shell**: POSIX-compatible shell (works on Android toybox/busybox)

## Usage

### Method 1: Execute via sh (Recommended)

This method works regardless of mount flags and doesn't require the target location to have exec permissions.

```bash
# 1. Check connected devices
adb devices -l

# 2. Push script to device
adb push tooling/test-files/create-test-files.sh /sdcard/

# 3. Execute via sh interpreter (target specific device with -s if multiple devices)
adb shell "sh /sdcard/create-test-files.sh /sdcard/ButlerTests"

# Or for specific device:
adb -s <DEVICE_SERIAL> shell "sh /sdcard/create-test-files.sh /sdcard/ButlerTests"
```

### Method 2: Use /data/local/tmp (Executable Location)

```bash
# Push to exec-allowed location
adb push tooling/test-files/create-test-files.sh /data/local/tmp/

# Make executable and run
adb shell "chmod +x /data/local/tmp/create-test-files.sh && /data/local/tmp/create-test-files.sh /sdcard/ButlerTests"
```

### Method 3: Inline Execution (No File Transfer)

```bash
# Execute directly without copying to device
adb shell 'sh -s /sdcard/ButlerTests' < tooling/test-files/create-test-files.sh
```

## Script Arguments

```bash
./create-test-files.sh [target_path]
```

- **target_path** (optional): Directory where test structure will be created
    - Default: Current directory (`.`)
    - Examples: `/sdcard/ButlerTests`, `/sdcard/TestFiles`, `/data/local/tmp/tests`

## Features

### Resume Capability

If the script is interrupted, simply run it again with the same target path. It will skip files that already exist and continue from where it left off.

### Progress Tracking

The script provides detailed progress information:

- Current operation and file being created
- File creation speed and time
- Progress percentage for batch operations
- Final summary with statistics

### Storage Validation

Checks available space before starting (requires 18GB minimum). Proceeds with a warning if space cannot be determined.

### Error Handling

- Graceful failure on insufficient space (ENOSPC)
- Validates required commands before execution
- Checks write permissions
- Provides meaningful error messages

## Output Structure

```
<target_path>/
├── adirwithlargefiles/
│   ├── file_100mb.bin
│   ├── file_200mb.bin
│   ├── file_400mb.bin
│   ├── file_800mb.bin
│   ├── file_1024mb.bin
│   ├── file_2048mb.bin
│   ├── file_4096mb.bin
│   └── file_8192mb.bin
├── adirwithmanyfiles/
│   ├── file_0001.dat
│   ├── file_0002.dat
│   ├── ...
│   └── file_4000.dat
└── adirwithnesteddata/
    ├── branch1/
    │   ├── level1_dir1/
    │   │   ├── file_1_1.txt
    │   │   ├── file_1_2.txt
    │   │   └── level2_dir1/
    │   │       └── ...
    │   └── level1_dir2/
    │       └── ...
    ├── branch2/
    │   └── ...
    └── branch5/
        └── ...
```

## Example Output

```
========================================
Android Test File Structure Creator
========================================
[INFO] Target path: /sdcard/ButlerTests
[INFO] Available space: 45GB (required: 18GB)
========================================
Creating Large Files (adirwithlargefiles/)
========================================
[INFO] [1/8] Creating large file: file_100mb.bin
[PROGRESS] Creating file_100mb.bin (100MB)...
[INFO] Created in 1s
[INFO] [2/8] Creating large file: file_200mb.bin
...
========================================
Creating Many Small Files (adirwithmanyfiles/)
========================================
[INFO] Creating 4000 files with random sizes (0-50KB)...
[PROGRESS] [500/4000] 12% complete
[PROGRESS] [1000/4000] 25% complete
...
========================================
Creating Nested Structure (adirwithnesteddata/)
========================================
[INFO] Creating balanced tree structure (10 levels deep)...
[PROGRESS] Creating branch 1/5...
[PROGRESS] Creating branch 2/5...
...
========================================
Summary
========================================
[INFO] Target path: /sdcard/ButlerTests
[INFO] Total directories: 1503
[INFO] Total files: 7508
[INFO] Total size: ~16650MB (~16GB)
[INFO] Time elapsed: 18m 42s

[INFO] Test file structure created successfully!
```

## Technical Details

### Random Data Generation

Uses `/dev/urandom` for realistic I/O patterns and non-compressible data. This provides:

- Real disk I/O performance testing
- Realistic compression behavior testing
- Varied file content for search/indexing tests

### Nested Structure Algorithm

Uses probability-based branching to create a balanced tree:

- Starts with 85% branching probability at level 1
- Decreases by 5% per level (prevents exponential explosion)
- Creates 2-3 folders per level (randomized)
- Places 2-3 small files in each folder
- Results in ~1,500 folders and ~3,500 files

### Portable Random Numbers

Uses `od` command for random number generation instead of `$RANDOM` (which is a bashism):

```bash
# Random number between 0-255
od -An -N1 -tu1 /dev/urandom | tr -d ' '

# Random number between 0-65535
od -An -N2 -tu2 /dev/urandom | tr -d ' '
```

### Color Output

Automatically detects terminal capabilities and uses colors when supported:

- GREEN: Informational messages
- YELLOW: Progress updates and warnings
- RED: Errors
- BLUE: Section headers

## Performance Notes

### Expected I/O Speeds

- Modern devices: 200-300 MB/s
- Older devices: 50-100 MB/s
- SD cards: 20-50 MB/s

### Bottlenecks

- Large files: Limited by `/dev/urandom` throughput and disk write speed
- Many files: Limited by filesystem metadata operations
- Nested structure: Limited by directory creation overhead

### Optimization Tips

If you need faster creation for testing:

1. Reduce the number of small files (edit `total_files=4000` to smaller value)
2. Reduce nested structure depth (edit `max_depth` in script)
3. Use `/dev/zero` instead of `/dev/urandom` for large files (edit script)

## Troubleshooting

### "Permission denied" when executing

- **Solution**: Use Method 1 (execute via `sh`) which doesn't require exec permissions
- **Why**: `/sdcard` is typically mounted with `noexec` flag

### "No space left on device"

- Check available space: `adb shell df -h /sdcard`
- Free up space or choose a different target path
- Reduce file sizes by editing the script

### Script runs very slowly

- Check device I/O speed: Look at MB/s in progress output
- Older/slower devices may take 30-45 minutes
- Consider reducing the number of files for quick testing

### "Cannot create directory"

- Check write permissions for target path
- Try a different path like `/sdcard/Download/`
- Check if path is valid on the device

### Verification

After running the script, verify the structure was created:

```bash
# Check directory structure
adb shell "ls -la /sdcard/ButlerTests/"

# Check large files
adb shell "ls -lh /sdcard/ButlerTests/adirwithlargefiles/"

# Count small files
adb shell "ls /sdcard/ButlerTests/adirwithmanyfiles/ | wc -l"

# Check nested depth
adb shell "find /sdcard/ButlerTests/adirwithnesteddata/ -type d | head -20"
```

## Cleanup

To remove the test structure after testing:

```bash
# Remove entire test directory
adb shell "rm -rf /sdcard/ButlerTests"

# Or remove specific sections
adb shell "rm -rf /sdcard/ButlerTests/adirwithlargefiles"
adb shell "rm -rf /sdcard/ButlerTests/adirwithmanyfiles"
adb shell "rm -rf /sdcard/ButlerTests/adirwithnesteddata"
```

## Use Cases

### File Operations Testing

- Copy/move large files between locations
- Bulk operations on thousands of files
- Cross-filesystem operations (SAF, root, ADB)

### Performance Testing

- Directory listing with many items
- Search operations in deep structures
- Memory usage with large file sets
- UI responsiveness with complex layouts

### Navigation Testing

- Deep path navigation (10 levels)
- Breadcrumb navigation
- Path segment handling
- Bookmark creation in nested structures

### Edge Cases

- Very large files (8GB)
- Empty files (in many files section)
- Long path names
- High file/folder density

## License

This tool is part of the Butler project and follows the same license terms.
