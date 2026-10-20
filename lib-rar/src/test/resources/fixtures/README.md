# RAR test fixtures

Two sets, each with a `manifest.json` that the tests read: `rar723/` was generated for this module
with RARLAB RAR 7.23, `libarchive/` holds libarchive's own RAR test archives.

## rar723/

Generated with RARLAB RAR 7.23 for Linux, used only to create and inspect these fixtures:

- Tarball: `https://www.rarlab.com/rar/rarlinux-x64-723.tar.gz`
- SHA-256: `759b4b6aa0d9f77131882162951193f3a0e54bf60e1d8dc4255aa308accab588`
- Banner: `RAR 7.23   Copyright (c) 1993-2026 Alexander Roshal   27 Jun 2026`

`gen_fixtures.py` writes the source tree, runs every `rar` command and writes `manifest.json`.
Sources are deterministic (SHA-256 based byte streams, fixed contents, every mtime
`2023-11-14T22:13:20.123456789Z`); the archives themselves are not byte-reproducible, because RAR
salts encryption and the huge input's mtime is the generation time.

```
python3 -I gen_fixtures.py --rar <dir>/rar/rar --out <dir>/out \
    --bsdtar <instrumented bsdtar> --huge
```

`--bsdtar` takes a libarchive v3.8.10 `bsdtar` built with one extra line in `parse_filter()`
(`fprintf(stderr, "RAR5FILTER type=%d len=%u\n", ...)`); its output is recorded per fixture as
`filters` (libarchive's numbering: 0 delta, 1 E8, 2 E8E9, 3 ARM). Encrypted and multi-volume
fixtures are not scanned.

Each manifest fixture has `files` (volumes in order), `commands` (exact `rar` invocations and their
working directory under the source root), `listPassword`, `headerEncrypted`, `solid`, `details`
(`rar lt`'s archive line), `filters`, `rarLt` (raw `rar lt -idc` output per volume) and `entries`.
An entry's `size`, `sha256` and `crc32` come from the source files; `ordinal` counts file headers in
archive order, excluding continuation headers; `version` is the `-ver` version record;
`password` is the password the entry was added with; `ltType`, `ltChecksum`, `ltCompression`,
`packedSize` and the split/solid/encrypted flags are what `rar lt` reported.

| Fixture | Command (run in the named source directory) | Notes |
|---|---|---|
| `stored.rar` | `rar a -idq -m0 -ts+ stored.rar hello.txt docs empty.txt emptydir unicode` | all three times, Unicode names, empty file and directory |
| `m1.rar`, `m3.rar`, `m5.rar` | `rar a -idq -m<n> <name> hello.txt docs empty.txt emptydir unicode` | tiny files stay `-m0` |
| `filter-e8.rar` | `rar a -idq -m3 -mce+ filter-e8.rar x86e9.bin` | E8 filter |
| `filter-e8e9.rar` | `rar a -idq -m3 filter-e8e9.rar x86e9.bin` | E8E9 filter (automatic) |
| `filter-delta.rar` | `rar a -idq -m3 -mc3d+ filter-delta.rar delta.bin` | delta filter, 3 channels |
| `solid.rar` | `rar a -idq -m3 -s solid.rar hello.txt docs empty.txt emptydir unicode` | |
| `dict-128k.rar`, `dict-1m.rar`, `dict-4m.rar` | `rar a -idq -m3 -md<size> <name> dictdata.bin` | |
| `dict-default.rar` | `rar a -idq -m3 dict-default.rar dictdata.bin` | RAR shrank the 32 MB default to 4 MB |
| `dict-5g-v1.rar` | `rar a -idq -m1 -mt4 -md5g dict-5g-v1.rar zero.bin` | see below |
| `symlinks.rar` | `rar a -idq -m3 -ol symlinks.rar target.txt sub to-target to-sub` | |
| `hardlinks.rar` | `rar a -idq -m3 -oh hardlinks.rar original.txt second.txt` | |
| `filecopy.rar` | `rar a -idq -m3 -oi:1 filecopy.rar first.bin copy.bin` | file reference |
| `blake2.rar` | `rar a -idq -m3 -htb blake2.rar hello.txt docs empty.txt emptydir unicode` | |
| `encrypted-data.rar` | `rar a -idq -m3 -ppassword ...` (basic tree) | |
| `encrypted-stored.rar` | `rar a -idq -m0 -ppassword encrypted-stored.rar hello.txt numbers.txt` | |
| `encrypted-headers.rar` | `rar a -idq -m3 -hppassword ...` (basic tree) | |
| `solid-encrypted.rar` | `rar a -idq -m3 -s -ppassword ...` (basic tree) | |
| `encrypted-unicode-password.rar` | `rar a -idq -m3 -ppässwört密码 ... hello.txt numbers.txt` | |
| `per-file-passwords.rar` | five appends, see manifest | `a.txt` (`one`), `b.txt` (`two`), `c.txt` (none), then `same.txt` with `-pone -ver` and `-ptwo -ver` |
| `comment.rar` | `rar a -idq -m3 -z../comment.txt comment.rar hello.txt numbers.txt` | `CMT` service header |
| `recovery.rar` | `rar a -idq -m3 -rr5% recovery.rar hello.txt numbers.txt` | `RR` service header |
| `quickopen.rar` | `rar a -idq -m3 -qo+ quickopen.rar hello.txt numbers.txt` | `QO` service header |
| `locked.rar` | `rar a -idq -m3 -k locked.rar hello.txt numbers.txt` | |
| `versions.rar` | `-m3 -ver` appends of `v1/note.txt` then `v2/note.txt` | older one has version 1 |
| `vol-plain.part*.rar` | `rar a -idq -m3 -v15k vol-plain.rar random.bin lorem.txt small.txt` | 3 volumes |
| `vol-solid.part*.rar` | `rar a -idq -m3 -s -v15k vol-solid.rar ...` | |
| `vol-hp.part*.rar` | `rar a -idq -m3 -hppassword -v15k vol-hp.rar ...` | |
| `vol-legacy.rar`, `.r00`, `.r01` | `cp` of `vol-plain.part1..3.rar` | see below |

Passwords: `password`, `one`, `two`, and `pässwört密码` for the Unicode one.

`dict-5g-v1.rar` is the real-world fixture for an unsupported algorithm version: its file header
stores compression info `0x1bc81` (raw algorithm version 1, RAR5-compat bit clear, `-md=4480m`), so
listing works while requesting its data must fail. `zero.bin` is a 4400 MiB sparse file
(`truncate`); generating it took about 11 GB RAM, 40 s and 4.4 GB of temporary disk, and the script
deletes the input afterwards. Its manifest entry carries the zero file's SHA-256 and CRC32.

RAR 7.23 does not write `.rNN` volume names ("doesn't use the extension based names for new
archives", `rar.txt` `-v` switch), and RAR5 headers carry no naming flag, so `vol-legacy.*` is a
renamed byte-identical copy of `vol-plain.part*.rar`.

Not producible with RAR 7.23:

- The RAR5 ARM filter (libarchive type 3, 32-bit ARM `BL`). Neither synthetic ARM32/ARM64 inputs
  nor real ARM ELF binaries, with or without `-mc` switches, made RAR 7.23 emit it, as recorded by
  the instrumented `bsdtar`. libarchive's `test_read_format_rar5_arm.rar` covers that filter
  instead (four ARM filter blocks, per the instrumented `bsdtar`).
  `test_read_format_rar5_arm_filter_on_window_boundary.rar` is a fuzzing sample whose first header
  declares size 0; libarchive v3.8.10 itself rejects it ("Too small block encountered (0 bytes)"),
  so it never reaches the filter.
- Dictionaries that are not a power of two below 4 GB: `-md3m`, `-md6m` and `-md192k` are rejected.
  RAR also shrinks the dictionary when the input is less than half of it, so each fixture records
  the size `rar lt` reported. Fraction and compat-bit handling are covered by crafted headers in
  the unit tests.

## libarchive/

From libarchive v3.8.10, `libarchive/test/`: every `test_read_format_rar5*.uu` plus the RAR4
`test_read_format_rar.rar.uu` (used for the RAR4 refusal test). `import_fixtures.py` uudecodes them
(file names come from the `.uu` names, since some `begin` lines say `-`) and runs
`test_read_format_rar5_extra_data_size_toolong.rar.Z` through `gzip -d`:

```
python3 -I import_fixtures.py --test-dir <libarchive>/libarchive/test --rar <dir>/rar/rar --out <dir>/out
```

`manifest.json` holds `rar lt` output per fixture, its exit code, the parsed entries and, where
RAR 7.23 extracted a regular file, its SHA-256. These archives have no separate sources, so the
hashes come from RAR's extraction. Several are deliberately broken (`ltExitCode` 3); they belong to
the hostile-input tests.

Passwords (`test_read_format_rar_encryption.c:29-33`): `test_read_format_rar5_encrypted.rar` stores
`a.txt` and `c.txt` unencrypted, `b.txt` with `password` and `d.txt` with `password2`. Every other
encrypted libarchive fixture uses `password`.

The libarchive distribution is Copyright by Tim Kientzle; the RAR5 tests are Copyright (c) 2018
Grzegorz Antoniak. libarchive's `COPYING` applies:

```
Copyright (c) 2003-2018 <author(s)>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer
   in this position and unchanged.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
