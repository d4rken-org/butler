#!/bin/sh
# Android Test File Structure Creator
# Creates three test directory structures for Butler testing
#
# Usage: ./create-test-files.sh [target_path]
#   target_path: Directory where test structure will be created (default: current directory)
#
# Creates:
#   - adirwithlargefiles/: 8 files (100MB to 8GB) with random data
#   - adirwithmanyfiles/: 4000 files with random sizes (0-50KB)
#   - adirwithnesteddata/: Balanced tree (~1500 folders, ~3500 files, 10 levels deep)
#
# Requirements: ~18GB free space, ~15-25 minutes runtime

set -e  # Exit on error

# Colors for output (if supported)
if command -v tput >/dev/null 2>&1 && [ -t 1 ]; then
    GREEN=$(tput setaf 2)
    YELLOW=$(tput setaf 3)
    RED=$(tput setaf 1)
    BLUE=$(tput setaf 4)
    RESET=$(tput sgr0)
else
    GREEN=""
    YELLOW=""
    RED=""
    BLUE=""
    RESET=""
fi

# Configuration
TARGET_PATH="${1:-.}"
REQUIRED_SPACE_GB=18
START_TIME=$(date +%s)

# Counters for summary
TOTAL_FILES=0
TOTAL_DIRS=0
TOTAL_SIZE_MB=0

# Helper functions
print_header() {
    echo "${BLUE}========================================${RESET}"
    echo "${BLUE}$1${RESET}"
    echo "${BLUE}========================================${RESET}"
}

print_info() {
    echo "${GREEN}[INFO]${RESET} $1"
}

print_warn() {
    echo "${YELLOW}[WARN]${RESET} $1"
}

print_error() {
    echo "${RED}[ERROR]${RESET} $1"
}

print_progress() {
    echo "${YELLOW}[PROGRESS]${RESET} $1"
}

# Check if required commands exist
check_commands() {
    for cmd in dd mkdir rm df; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            print_error "Required command '$cmd' not found"
            exit 1
        fi
    done
}

# Get available space in GB
get_available_space_gb() {
    df -BG "$TARGET_PATH" 2>/dev/null | awk 'NR==2 {gsub(/G/,"",$4); print int($4)}'
}

# Check available storage
check_storage() {
    local avail_gb=$(get_available_space_gb)

    if [ -z "$avail_gb" ]; then
        print_warn "Could not determine available space, proceeding anyway"
        return 0
    fi

    if [ "$avail_gb" -lt "$REQUIRED_SPACE_GB" ]; then
        print_error "Insufficient space: ${avail_gb}GB available, ${REQUIRED_SPACE_GB}GB required"
        exit 1
    fi

    print_info "Available space: ${avail_gb}GB (required: ${REQUIRED_SPACE_GB}GB)"
}

# Create a file with random data
create_random_file() {
    local filepath="$1"
    local size_mb="$2"

    if [ -f "$filepath" ]; then
        print_warn "File already exists, skipping: $filepath"
        return 0
    fi

    print_progress "Creating $(basename "$filepath") (${size_mb}MB)..."

    local start=$(date +%s)
    dd if=/dev/urandom of="$filepath" bs=1M count="$size_mb" 2>&1 | grep -v records
    local end=$(date +%s)
    local duration=$((end - start))

    print_info "Created in ${duration}s"

    TOTAL_FILES=$((TOTAL_FILES + 1))
    TOTAL_SIZE_MB=$((TOTAL_SIZE_MB + size_mb))
}

# Create large files section
create_large_files() {
    print_header "Creating Large Files (adirwithlargefiles/)"

    local dir="$TARGET_PATH/adirwithlargefiles"
    mkdir -p "$dir"
    TOTAL_DIRS=$((TOTAL_DIRS + 1))

    local sizes="100 200 400 800 1024 2048 4096 8192"
    local total_files=8
    local current=0

    for size_mb in $sizes; do
        current=$((current + 1))
        local filename="file_${size_mb}mb.bin"

        print_info "[$current/$total_files] Creating large file: $filename"
        create_random_file "$dir/$filename" "$size_mb"
    done

    print_info "Large files section complete"
}

# Create many small files section
create_many_files() {
    print_header "Creating Many Small Files (adirwithmanyfiles/)"

    local dir="$TARGET_PATH/adirwithmanyfiles"
    mkdir -p "$dir"
    TOTAL_DIRS=$((TOTAL_DIRS + 1))

    local total_files=4000
    local report_interval=500
    local total_size=0

    print_info "Creating $total_files files with random sizes (0-50KB)..."

    local i=1
    while [ $i -le $total_files ]; do
        local filename=$(printf "file_%04d.dat" $i)
        local filepath="$dir/$filename"

        if [ ! -f "$filepath" ]; then
            # Random size between 0 and 50KB
            local size_kb=$(($(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 51))

            if [ $size_kb -eq 0 ]; then
                # Create empty file
                touch "$filepath"
            else
                dd if=/dev/urandom of="$filepath" bs=1K count=$size_kb 2>/dev/null
            fi

            total_size=$((total_size + size_kb))
            TOTAL_FILES=$((TOTAL_FILES + 1))
        fi

        # Progress reporting
        if [ $((i % report_interval)) -eq 0 ] || [ $i -eq $total_files ]; then
            local percent=$((i * 100 / total_files))
            print_progress "[$i/$total_files] ${percent}% complete"
        fi

        i=$((i + 1))
    done

    local total_size_mb=$((total_size / 1024))
    TOTAL_SIZE_MB=$((TOTAL_SIZE_MB + total_size_mb))

    print_info "Many files section complete (total size: ${total_size_mb}MB)"
}

# Create nested directory structure recursively
create_nested_structure_recursive() {
    local base_path="$1"
    local current_depth="$2"
    local max_depth="$3"
    local branch_probability="$4"

    if [ $current_depth -gt $max_depth ]; then
        return
    fi

    # Create 2-3 files in current directory
    local num_files=$((2 + $(od -An -N1 -tu1 /dev/urandom | tr -d ' ') % 2))
    local i=1
    while [ $i -le $num_files ]; do
        local filename="file_${current_depth}_${i}.txt"
        local filepath="$base_path/$filename"

        if [ ! -f "$filepath" ]; then
            local size_kb=$((1 + $(od -An -N1 -tu1 /dev/urandom | tr -d ' ') % 10))
            dd if=/dev/urandom of="$filepath" bs=1K count=$size_kb 2>/dev/null
            TOTAL_FILES=$((TOTAL_FILES + 1))
            TOTAL_SIZE_MB=$((TOTAL_SIZE_MB + size_kb / 1024))
        fi

        i=$((i + 1))
    done

    # Create 2-3 subdirectories and recurse
    local num_dirs=$((2 + $(od -An -N1 -tu1 /dev/urandom | tr -d ' ') % 2))

    # Reduce branching as we go deeper
    local random_val=$(($(od -An -N1 -tu1 /dev/urandom | tr -d ' ') % 100))
    if [ $random_val -ge $branch_probability ]; then
        return
    fi

    local j=1
    while [ $j -le $num_dirs ]; do
        local dirname="level${current_depth}_dir${j}"
        local dirpath="$base_path/$dirname"

        if [ ! -d "$dirpath" ]; then
            mkdir -p "$dirpath"
            TOTAL_DIRS=$((TOTAL_DIRS + 1))
        fi

        # Recurse with reduced probability
        local next_probability=$((branch_probability - 5))
        create_nested_structure_recursive "$dirpath" $((current_depth + 1)) $max_depth $next_probability

        j=$((j + 1))
    done
}

# Create nested directory structure
create_nested_structure() {
    print_header "Creating Nested Structure (adirwithnesteddata/)"

    local dir="$TARGET_PATH/adirwithnesteddata"
    mkdir -p "$dir"
    TOTAL_DIRS=$((TOTAL_DIRS + 1))

    print_info "Creating balanced tree structure (10 levels deep)..."

    # Create 5 main branches
    local branch_num=1
    while [ $branch_num -le 5 ]; do
        local branch_path="$dir/branch$branch_num"

        if [ ! -d "$branch_path" ]; then
            mkdir -p "$branch_path"
            TOTAL_DIRS=$((TOTAL_DIRS + 1))
        fi

        print_progress "Creating branch $branch_num/5..."

        # Create nested structure with 85% branching probability at start
        create_nested_structure_recursive "$branch_path" 1 10 85

        branch_num=$((branch_num + 1))
    done

    print_info "Nested structure section complete"
}

# Print summary
print_summary() {
    local end_time=$(date +%s)
    local duration=$((end_time - START_TIME))
    local minutes=$((duration / 60))
    local seconds=$((duration % 60))

    print_header "Summary"
    print_info "Target path: $TARGET_PATH"
    print_info "Total directories: $TOTAL_DIRS"
    print_info "Total files: $TOTAL_FILES"
    print_info "Total size: ~${TOTAL_SIZE_MB}MB (~$((TOTAL_SIZE_MB / 1024))GB)"
    print_info "Time elapsed: ${minutes}m ${seconds}s"
    echo ""
    print_info "${GREEN}Test file structure created successfully!${RESET}"
}

# Main execution
main() {
    print_header "Android Test File Structure Creator"

    print_info "Target path: $TARGET_PATH"

    # Validate environment
    check_commands
    check_storage

    # Create base directory
    mkdir -p "$TARGET_PATH"

    # Create test structures
    create_large_files
    echo ""
    create_many_files
    echo ""
    create_nested_structure
    echo ""

    # Print summary
    print_summary
}

# Run main function
main
