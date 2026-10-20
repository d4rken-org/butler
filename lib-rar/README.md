# Kotlin RAR

A Kotlin/JVM reader for RAR5 archives used by Butler. It reads an archive front to back from an
`InputStream`: entry listing, entry data, solid streams, multi-volume sets, encryption and
checksums. It never writes archives. The module has no dependencies beyond the Kotlin standard
library and the JDK, and targets JVM 17.

```kotlin
import eu.darken.rar.*
import java.io.File
import java.util.concurrent.CancellationException

val first = File("photos.part1.rar")
val options = RarOptions(
    // Called on the reading thread; return null to give up. The reader wipes the array.
    passwordProvider = RarPasswordProvider { request -> askForPassword(request) },
    volumeProvider = RarVolumeProvider { index ->
        RarVolumeNames.volumeName(first.name, index)
            ?.let { File(first.parentFile, it) }
            ?.takeIf { it.isFile }
            ?.inputStream()
    },
    limits = RarLimits(maxWindowBytes = 64L shl 20),
    cancellation = { if (cancelled.get()) throw CancellationException() },
)

// Listing only: entry data is skipped, never decoded.
RarReader(first.inputStream(), options.copy(listOnly = true)).use { reader ->
    generateSequence { reader.nextEntry() }.forEach { println("${it.name} ${it.size}") }
}

RarReader(first.inputStream(), options).use { reader ->
    while (true) {
        val entry = reader.nextEntry() ?: break
        if (entry.isDirectory || entry.redirection != null) continue
        reader.entryStream().use { consume(entry.name, it) }
    }
}
```

The reader owns the stream it is given, and every volume stream it opens, and closes them,
including when the constructor throws. `entryStream()` may be called once per entry; the stream
stays valid until the next `nextEntry()` or `close()`, and its checksum is verified on the read that
delivers the entry's last byte. Entries of a redirection type (links, file references) carry no data
of their own and yield an empty stream. With `listOnly`, `entryStream()` is unavailable.

## Supported scope

- RAR5 archives that start with the RAR5 signature. Self-extracting executables are not scanned
  for an embedded archive and fail as corrupt.
- Stored and LZ-compressed entries (methods 1 to 5), algorithm version 0, and version 1 with the
  RAR5-compatibility bit.
- All four RAR5 filters: delta, E8, E8E9 and ARM.
- Solid archives, multi-volume sets (`.partN.rar` and legacy `.rar`/`.rNN` naming) and entries
  split across volumes.
- AES-256 encryption of headers (`rar -hp`) and of entry data (`rar -p`), with the password check
  value where the archive stores one, and keyed checksum MACs.
- CRC32 and BLAKE2sp checksums.

Refused with a `RarUnsupportedException`: RAR4 archives (`Rar4`), algorithm version 1
without the compatibility bit and any later version (`AlgorithmVersion`), dictionaries over
`RarLimits.maxWindowBytes` (`DictionaryTooLarge`), compression methods above 5 (`UnknownMethod`),
filter types beyond the four (`UnknownFilter`), encryption other than AES-256
(`UnsupportedEncryption`), a later volume given as the first (`NotFirstVolume`) and archives over
the other limits (`LimitExceeded`).

## Volumes

Volume sets are read through `RarOptions.volumeProvider`. The reader is given the first volume;
each time a volume ends with more to come, it calls `open(volumeIndex)` for the next one (1, 2, …,
each once, in order) and closes the previous stream. `RarVolumeNames.volumeName` derives the file
names of `.partN.rar` and legacy `.rar`/`.r00` sets from the first volume's name. Without a
provider, or when it returns null, `nextEntry()` fails with `RarMissingVolumeException` where the
next volume is needed, and so does reading the data of an entry that continues there. An entry
split across volumes is listed once: its `packedSize` is the first part's and its `checksum` is
null, because the stored value of every part but the last covers that part's packed bytes. Those
part checksums are verified while the data is read, the file's own checksum at its end. Solid
streams, the dictionary reservation and the AES-CBC chain of an encrypted entry continue across
volumes. In a set with encrypted headers, each volume's header key is reused while its check value
accepts it.

Each volume must start with a RAR5 signature, match the first volume's header encryption and
declare its own volume number, and a continued entry must keep its name, size, checksum types and
encryption parameters; anything else is a `RarCorruptException`. RAR5 volumes carry no identifier
of the set they belong to, so a volume of an unrelated set that passes these checks is not detected
until its data fails to decode or its checksums fail, or not at all if it continues no entry.

## Passwords

Passwords come from `RarOptions.passwordProvider`, called synchronously with a
`RarPasswordRequest`: once with scope `HEADERS` in the constructor of an archive with encrypted
headers, and with scope `ENTRY`, the entry's ordinal and name, when an encrypted entry's data is
needed. The header password is not reused for entries. The reader converts the returned `CharArray`
to UTF-8, wipes it, derives the keys and keeps only those, in a small cache per salt, so files
sharing a salt ask once. Keys are wiped on `close()` and when the reader fails. A null answer means
`RarPasswordRequiredException`, a password the archive's check value rejects
`RarWrongPasswordException` with `certain = true`. An archive without check values only shows a
wrong password once the data fails to decode or its checksum fails, as a `RarWrongPasswordException`
with `certain = false`. For encrypted entries the stored CRC32/BLAKE2sp are usually keyed MACs,
verified with the password's hash key. Key derivation counts above `RarLimits.maxKdfLog2` are
refused with `LimitExceeded` before the password is asked for. The limit defaults to 24 (2^24
PBKDF2 iterations), which is also the highest value `RarLimits` accepts.

## Threading and cancellation

A reader and its entry streams are not thread-safe: use them from one thread at a time. The
password provider, the volume provider, the memory budget and the cancellation hook are called
synchronously on that thread.

`RarOptions.cancellation` is called regularly while the reader works, also while it skips data or
derives keys. Whatever it throws propagates unchanged, as do exceptions from the caller's streams,
the volume provider and the memory budget. The hook cannot interrupt a read that blocks inside the
caller's `InputStream`: to abandon one, close that stream from another thread, and the exception
the blocked read then throws surfaces unchanged.

## Errors

All format errors are `RarException`s, which are `IOException`s: `RarCorruptException`,
`RarUnsupportedException`, `RarPasswordRequiredException`, `RarWrongPasswordException` and
`RarMissingVolumeException`.

A `RarCorruptException`, a `RarMissingVolumeException`, or any exception from the underlying
streams, the volume provider, the cancellation hook or the memory budget makes every later call
fail; the reader is then only good for `close()`. Exceptions from `entryStream()` and its reads that
only concern that entry (password, an unsupported feature such as an unknown filter type, a split
entry without any volume provider) leave the reader usable, so later non-solid entries still read.

Skipping an entry of a solid archive decodes and discards its data, because later entries need the
dictionary it builds, so skipping an encrypted solid entry asks for its password. When decoding is
impossible (no password, a wrong one, an unknown filter type), the skipped entry blocks the solid
stream: `entryStream()` on a later solid entry fails with the blocking entry's ordinal and name,
until the next non-solid entry starts a fresh stream. `listOnly` decodes nothing, so it never
blocks and never asks for entry passwords.

## Memory

The LZ dictionary is the one allocation reserved from the memory budget. It is stored in chunks of 256 KiB (one
chunk for smaller dictionaries) that are allocated as the stream grows, so a 4 MiB dictionary
holding 10 KiB of output occupies one chunk.

Before an entry starts a new LZ stream, the reader releases the previous stream's reservation and
reserves the new one from `RarOptions.memoryBudget`: the declared dictionary, capped at the entry's
size in a non-solid archive and at `RarLimits.maxWindowBytes` otherwise, rounded up to whole chunks.
Solid entries continue the stream and its reservation. Stored entries reserve nothing. The chunks
never grow past the reservation, which is released when the next stream starts, when the reader
fails and on `close()`.
`acquire` may block, for example on a budget shared by several readers, and may throw.

A dictionary larger than `maxWindowBytes` fails with `RarUnsupportedReason.DictionaryTooLarge`, when
the entry stream is opened if the entry's size shows it, otherwise once the stream grows past the
limit. The other `RarLimits` bound header size, entry count, name length, extra records per header,
volume count, the filters pending in a stream and the key derivation count.

## Licensing

- The header and stream layer is a Kotlin port of libarchive v3.8.10
  `libarchive/archive_read_support_format_rar5.c`, Copyright (c) 2018 Grzegorz Antoniak,
  BSD-2-Clause. Each ported file keeps libarchive's notice.
- The LZ decoder, filters and window handling are a Kotlin port of the same libarchive file, under
  the same notice.
- BLAKE2s/BLAKE2sp is a Kotlin port of libarchive's copy of the BLAKE2 reference code
  (`archive_blake2s_ref.c`, `archive_blake2sp_ref.c`), Copyright 2012 Samuel Neves, usable under
  CC0 1.0, the OpenSSL license or Apache 2.0; `Blake2sp.kt` keeps that notice.
- The crypto layer, `Rar5Crypto.kt`, is implemented from the RARLAB technote
  (https://www.rarlab.com/technote.htm); key derivation, password check value and checksum MAC
  conversion follow 7-Zip's `CPP/7zip/Crypto/Rar5Aes.cpp` (Copyright (C) Igor Pavlov). That file is
  licensed LGPL-2.1-or-later and keeps the notice. AES, HMAC-SHA256 and SHA-256 come from the JDK
  (`javax.crypto`, `java.security`).
- 7-Zip's `Rar5Handler` (LGPL-2.1-or-later) is used as a format reference for compression info,
  encrypted header blocks, checksum verification and volume continuation. No UnRAR code is used or
  referenced.
- Test fixture provenance: `src/test/resources/fixtures/README.md`. The fuzz seeds under
  `src/test/resources/eu/darken/rar/RarReaderFuzzTestInputs/` are copies of those fixtures.

## Tests

Run from the repository root with its configured JDK:

```sh
./gradlew :lib-rar:test
./gradlew :lib-rar:rarSweep
./gradlew :lib-rar:rarFuzz -Prar.fuzz.duration=10m
./gradlew :lib-rar:rarPerf
./gradlew :lib-rar:koverXmlReport :lib-rar:koverHtmlReport
```

`test` runs the unit, fixture and hostile-input tests, including truncation and bit-flip sweeps
judged by `SweepCheck` (outcome, memory budget, allocation, closed streams), in a 384 MB heap with a
15 minute timeout, so a hostile input that hangs or over-allocates fails the build. It also replays
the seeds of the `@FuzzTest` methods. `rarSweep` runs the sweeps too slow for `test` (tag
`rar-sweep`).

`rarFuzz` fuzzes `RarReaderFuzzTest` with Jazzer for `rar.fuzz.duration` (default `60s`; Jazzer
accepts `s`, `m`, `h`). Each input's first byte picks the read mode (read, `listOnly`, skip), the
rest is the archive; a run may only end in success or a `RarException`. The task runs in
`build/reports/rar-fuzz`, where Jazzer keeps its generated corpus (`.cifuzz-corpus`, carried over
between runs) and writes `crash-*` inputs and `Crash_*.java` reproducers. A finding fails the task.
To keep one as a regression test, copy its `crash-*` file into
`src/test/resources/eu/darken/rar/RarReaderFuzzTestInputs/reader/`; seeds there are fixtures behind
a mode byte (0 read, 1 `listOnly`, 2 skip).

`rarPerf` times decoding of in-memory archives: LZ output of a solid volume set and of a compressed
archive, a stored entry, the E8 and delta filters, AES decryption with decoding (key derivation
excluded, and timed on its own at the fixture's count), and `listOnly` over a volume set. Each
scenario has a warm-up and a measured pass. Results are printed and written to
`lib-rar/build/reports/rar-perf/rar.json`; there are no thresholds.

Coverage comes from `test` alone: Kover does not instrument `rarSweep`, `rarFuzz` or `rarPerf`. The
HTML report is at `lib-rar/build/reports/kover/html/index.html`, the XML at
`lib-rar/build/reports/kover/report.xml`. The `RAR library` job in
`.github/workflows/code-checks.yml` runs the Kover reports on pull requests and pushes to main,
fails unless every test suite ran without failures or skips, writes line and branch coverage into
the job summary and uploads the reports. Sweeps, fuzzing and benchmarks do not run in CI.

The fixtures are regenerated with `src/test/resources/fixtures/rar723/gen_fixtures.py` (RAR 7.23)
and `src/test/resources/fixtures/libarchive/import_fixtures.py`; see the fixtures README for both.
