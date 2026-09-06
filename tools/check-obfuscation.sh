#!/usr/bin/env bash
# Verifies that the gplay release is obfuscated and that everything which must survive renaming did.
# Run from the repo root after:
#   ./gradlew :app:assembleGplayRelease :app:assembleFossRelease :app:bundleGplayRelease
# mapping.txt is ~160 MB, so every access here streams (head/grep -F/awk); never read it whole.
set -euo pipefail

GPLAY_CONFIG="app/build/outputs/mapping/gplayRelease/configuration.txt"
FOSS_CONFIG="app/build/outputs/mapping/fossRelease/configuration.txt"
GPLAY_MAPPING="app/build/outputs/mapping/gplayRelease/mapping.txt"

failures=0

ok() {
    echo "ok: $1"
}

fail() {
    echo "FAIL: $1"
    failures=$((failures + 1))
}

require_file() {
    if [ ! -f "$1" ]; then
        fail "$2 missing: $1"
        return 1
    fi
    return 0
}

check_dontobfuscate() {
    if require_file "$GPLAY_CONFIG" "gplayRelease configuration"; then
        local count
        count=$(grep -c '^-dontobfuscate' "$GPLAY_CONFIG" || true)
        if [ "$count" -eq 0 ]; then
            ok "gplayRelease has no -dontobfuscate"
        else
            fail "gplayRelease has $count -dontobfuscate rule(s), it must have none"
        fi
    fi

    if require_file "$FOSS_CONFIG" "fossRelease configuration"; then
        local count
        count=$(grep -c '^-dontobfuscate' "$FOSS_CONFIG" || true)
        if [ "$count" -ge 1 ]; then
            ok "fossRelease has -dontobfuscate ($count rule(s))"
        else
            fail "fossRelease has no -dontobfuscate, foss must stay unobfuscated"
        fi
    fi
}

check_renamed_ratio() {
    require_file "$GPLAY_MAPPING" "gplayRelease mapping" || return

    local counts total renamed
    counts=$(awk -F' -> ' '
        /^eu\.darken\.butler\./ && / -> / && !/R8\$\$REMOVED/ {
            total++
            if ($1 ":" != $2) renamed++
        }
        END { print total+0, renamed+0 }
    ' "$GPLAY_MAPPING")
    total=${counts% *}
    renamed=${counts#* }

    if [ "$renamed" -ge 1000 ]; then
        ok "renamed app classes: $renamed (>= 1000)"
    else
        fail "renamed app classes: $renamed (expected >= 1000 of $total)"
    fi

    if [ "$total" -gt 0 ] && [ $((renamed * 100)) -ge $((total * 25)) ]; then
        ok "renamed share: $renamed/$total (>= 25%)"
    else
        fail "renamed share: $renamed/$total (expected >= 25%)"
    fi
}

check_identity_mappings() {
    require_file "$GPLAY_MAPPING" "gplayRelease mapping" || return

    local kept=(
        'eu.darken.butler.common.root.service.RootServiceConnection'
        'eu.darken.butler.common.root.service.RootServiceConnection$Stub'
        'eu.darken.butler.common.root.service.RootServiceConnection$Stub$Proxy'
        'eu.darken.butler.common.adb.AdbServiceConnection'
        'eu.darken.butler.common.adb.AdbServiceConnection$Stub'
        'eu.darken.butler.common.adb.AdbServiceConnection$Stub$Proxy'
        'eu.darken.butler.common.root.service.RootHost'
        'eu.darken.butler.common.adb.service.AdbHost'
        'eu.darken.butler.BuildConfig'
        'eu.darken.butler.common.files.errors.PathNotFoundException'
        'eu.darken.butler.main.ui.MainActivity'
    )

    local cls
    for cls in "${kept[@]}"; do
        if grep -qF "$cls -> $cls:" "$GPLAY_MAPPING"; then
            ok "name kept: $cls"
        else
            fail "name not kept: $cls"
        fi
    done
}

check_bundle_mapping() {
    local aab
    aab=$(find app/build/outputs/bundle/gplayRelease -maxdepth 1 -name '*.aab' -print -quit 2>/dev/null || true)
    if [ -z "$aab" ]; then
        fail "no gplayRelease .aab found in app/build/outputs/bundle/gplayRelease"
        return
    fi

    if unzip -l "$aab" 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map' \
        | grep -q 'proguard.map'; then
        ok "bundle carries the mapping: $(basename "$aab")"
    else
        fail "bundle has no BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map: $(basename "$aab")"
    fi
}

# The SourceFile attribute of an obfuscated class holds "r8-map-id-<pg_map_id>". Error reports lift
# that id out of stack traces (ErrorReportPayload.extractMapId), so a -keepattributes SourceFile or
# -renamesourcefileattribute rule would silently null out mapId for every report.
check_map_id() {
    require_file "$GPLAY_MAPPING" "gplayRelease mapping" || return

    local map_id
    map_id=$(head -n 20 "$GPLAY_MAPPING" | grep -o '# pg_map_id: [0-9a-f]\{64\}' | head -n 1 | awk '{print $3}')
    if [ -z "$map_id" ]; then
        fail "no '# pg_map_id: <64 hex>' header in the first 20 lines of mapping.txt"
        return
    fi

    local apk
    apk=$(find app/build/outputs/apk/gplay/release -maxdepth 1 -name '*-GPLAY-RELEASE-UPLOAD.apk' -print -quit 2>/dev/null || true)
    if [ -z "$apk" ]; then
        fail "no *-GPLAY-RELEASE-UPLOAD.apk found in app/build/outputs/apk/gplay/release"
        return
    fi

    if unzip -p "$apk" classes.dex | strings | grep -q "r8-map-id-$map_id"; then
        ok "map id survives in the dex: r8-map-id-$map_id"
    else
        fail "r8-map-id-$map_id not found in $(basename "$apk") classes.dex"
    fi
}

check_dontobfuscate
check_renamed_ratio
check_identity_mappings
check_bundle_mapping
check_map_id

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed"
    exit 1
fi

echo "all checks passed"
