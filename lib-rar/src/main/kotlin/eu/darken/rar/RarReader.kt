/*-
* Copyright (c) 2018 Grzegorz Antoniak (http://antoniak.org)
* All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions
* are met:
* 1. Redistributions of source code must retain the above copyright
*    notice, this list of conditions and the following disclaimer.
* 2. Redistributions in binary form must reproduce the above copyright
*    notice, this list of conditions and the following disclaimer in the
*    documentation and/or other materials provided with the distribution.
*
* THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
* IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
* OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
* IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
* INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
* NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
* DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
* THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
* (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
* THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

// Kotlin port of the base block loop and the data paths of libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c (process_base_block, rar5_read_header,
// rar5_read_data, rar5_read_data_skip, verify_checksums, advance_multivolume). Where merge_block
// copies a compressed block that spans volumes into one buffer, the packed data of a split entry
// is read here as one stream across its parts. Decryption follows the RARLAB technote and 7-Zip's
// Rar5Handler.cpp (ReadBlockHeader, CHash::Check); continuation checks and part checksums follow
// the same file (CItem::IsNextForItem, CVolsInStream::Read). The crypto itself is in Rar5Crypto.kt.

package eu.darken.rar

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

/**
 * Reads a RAR5 archive front to back. The constructor reads the archive's main header. For an
 * archive with encrypted headers it first asks [RarOptions.passwordProvider] for the HEADERS
 * password and throws [RarPasswordRequiredException] without one, [RarWrongPasswordException] for
 * one that fails the archive's check value. Encrypted entries ask for their own password when their
 * data is needed; derived keys are cached per salt for the reader's lifetime and wiped on [close].
 *
 * A volume set continues through [RarOptions.volumeProvider]: at the end of each volume the reader
 * opens the next one. An entry split across volumes is listed once, from its first part, and its
 * data is read through all of its parts.
 *
 * The reader owns [firstVolume] and every stream the volume provider returns, and closes them,
 * also when the constructor throws. A [RarCorruptException], a [RarMissingVolumeException] or any
 * exception from the underlying streams, the volume provider, the cancellation hook or the memory
 * budget makes every later call throw. Exceptions from [entryStream] and its reads that only
 * concern that entry (password, unsupported feature such as an unknown filter type, a split entry
 * without any volume provider) leave the reader usable.
 */
public class RarReader internal constructor(
    firstVolume: InputStream,
    private val options: RarOptions,
    private val windowChunkSize: Int,
    private val keyDerivation: Rar5KeyDerivation = Rar5Crypto.DERIVATION,
) : Closeable {
    public constructor(firstVolume: InputStream, options: RarOptions = RarOptions()) :
        this(firstVolume, options, Rar5Window.DEFAULT_CHUNK_SIZE)

    private val limits = options.limits
    private val input = ArchiveInput(firstVolume, options.cancellation)

    /** The volume [input] reads, 0 for the first. */
    private var volumeIndex = 0

    /** The first block after the signature when the archive has no main header. */
    private var pending: Block? = null
    private var current: Current? = null
    private var nextOrdinal = 0
    private var generation = 0L
    private var finished = false
    private var closed = false
    private var failure: Throwable? = null

    /** The LZ stream later solid entries continue, null when there is none. */
    private var decoder: Rar5Decoder? = null

    /** Bytes held from [RarOptions.memoryBudget] for [decoder]'s window. */
    private var reserved = 0L

    /** Why the current solid stream cannot be decoded; solid entries rethrow it until the next reset. */
    private var blocker: (() -> RarException)? = null

    private val discard by lazy { ByteArray(DISCARD_SIZE) }

    /** Keys for the headers after HEAD_CRYPT, null while headers are plain. */
    private var headerKeys: Rar5Keys? = null

    /** The archive encryption header [headerKeys] were derived for. */
    private var headerCrypt: CryptHeader? = null

    /** The header password matched the archive's check value. */
    private var headerProven = false

    /** A header decrypted with [headerKeys] passed its CRC. */
    private var headerVerified = false

    /** Entry keys, least recently used first. */
    private val keyCache = ArrayDeque<CachedKeys>()

    /** Filters run by this reader, indexed by [Rar5Filters] type. */
    internal val filterCounts = IntArray(Rar5Filters.TYPE_COUNT)

    internal val reservedBytes: Long get() = reserved

    internal val windowStorageBytes: Long get() = decoder?.window?.allocated ?: 0L

    public val archive: RarArchiveInfo = openOrClose()

    /**
     * The next file or directory, or null at the end of the archive's last volume. When the next
     * volume is needed but the provider is absent or has none, this throws
     * [RarMissingVolumeException]. Skipping an entry of a solid archive decodes and discards its
     * remaining data, asking for its password if it is encrypted. If it cannot be decoded (for
     * example without a correct password), later solid entries fail with that entry's reason.
     */
    public fun nextEntry(): RarEntry? {
        checkUsable()
        generation++
        val previous = current
        current = null
        if (finished) return null
        return poisonOnFailure {
            if (previous != null) finishEntry(previous)
            readNextEntry()
        }
    }

    /**
     * The current entry's data, once per entry. Valid until the next [nextEntry] or [close];
     * closing it neither closes nor drains the reader. The checksum is verified on the read that
     * delivers the last byte. Directories and redirections (links, file references) yield an empty
     * stream.
     *
     * An encrypted entry asks [RarOptions.passwordProvider] for its password, unless a cached key
     * fits it. Without one this throws [RarPasswordRequiredException], with one that fails the
     * entry's check value [RarWrongPasswordException]; neither poisons the reader, but in a solid
     * archive they block the rest of the solid stream. Without a check value, a wrong password shows
     * as a [RarWrongPasswordException] that is not [RarWrongPasswordException.certain], once the
     * data fails to decode or its checksum fails.
     *
     * The data of a split entry continues through the later volumes; without a volume provider this
     * throws [RarMissingVolumeException] up front. Each part that is followed by another has its
     * stored checksum, which covers that part's packed bytes, verified once it was read.
     */
    public fun entryStream(): InputStream {
        checkUsable()
        check(!options.listOnly) { "entryStream() is unavailable in listOnly mode" }
        val cur = checkNotNull(current) { "No current entry" }
        check(!cur.streamRequested) { "entryStream() was already called for this entry" }
        cur.streamRequested = true

        val header = cur.header
        if (header.isDirectory || header.redirection != null) return EntryStream(cur, length = 0, lz = false, checked = false)
        // A later entry of a blocked solid stream reports the entry that blocked it.
        if (header.isLz && header.compression.solid) blocker?.let { throw it() }
        entryBlocker(cur)?.let { throw it() }
        if (header.splitAfter && options.volumeProvider == null) throw RarMissingVolumeException(volumeIndex + 1)
        if (header.crypt != null) {
            poisonOnFailure { checkEncryptedLayout(cur) }
            when (val outcome = poisonOnFailure { entryKeys(cur) }) {
                is KeyOutcome.Ready -> cur.useKeys(outcome)
                is KeyOutcome.Unavailable -> {
                    // A solid stream cannot continue past an entry it cannot decode.
                    if (header.isLz && archive.solid) {
                        cur.abandoned = true
                        poisonOnFailure { blockStream(outcome.reason) }
                    }
                    throw outcome.reason()
                }
            }
        }
        if (!header.isLz) {
            // Encrypted stored data is padded to whole AES blocks; the padding is not delivered. A
            // split entry's packed total is only known at its last part, where the stream checks it.
            if (header.crypt == null && !header.splitAfter && header.size != null && header.size != header.dataSize) {
                throw poison(RarCorruptException("Stored entry ${cur.entry.ordinal} has packed size ${header.dataSize} but size ${header.size}"))
            }
            val length = if (header.splitAfter) header.size else header.size ?: header.dataSize
            return EntryStream(cur, length = length, lz = false, source = packedSource(cur))
        }
        poisonOnFailure { beginDecode(cur) }
        return EntryStream(cur, length = header.size, lz = true)
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        current = null
        try {
            releaseStream()
        } finally {
            wipeKeys()
            input.close()
        }
    }

    private fun openOrClose(): RarArchiveInfo = try {
        open()
    } catch (t: Throwable) {
        wipeKeys()
        try {
            input.close()
        } catch (closeFailure: Throwable) {
            t.addSuppressed(closeFailure)
        }
        throw t
    }

    private fun open(): RarArchiveInfo {
        val signature = ByteArray(RarSignature.RAR5_SIZE)
        val length = input.readUpTo(signature, 0, signature.size)
        when (RarSignature.detect(signature, length)) {
            RarSignature.Kind.RAR4 -> throw RarUnsupportedException(RarUnsupportedReason.Rar4)
            RarSignature.Kind.UNKNOWN -> throw RarCorruptException("Not a RAR5 archive")
            RarSignature.Kind.RAR5 -> Unit
        }
        val block = readBlock() ?: throw ArchiveInput.unexpectedEnd()
        if (block.type != HeaderType.CRYPT) return archiveInfo(block, headerEncrypted = false)
        startHeaderDecryption(CryptHeader.parse(block))
        return archiveInfo(readBlock() ?: throw ArchiveInput.unexpectedEnd(), headerEncrypted = true)
    }

    private fun archiveInfo(block: Block, headerEncrypted: Boolean): RarArchiveInfo {
        return when (block.type) {
            HeaderType.MAIN -> {
                val main = MainHeader.parse(block, limits)
                if (main.volumeNumber != 0) throw RarUnsupportedException(RarUnsupportedReason.NotFirstVolume(main.volumeNumber))
                input.skipFully(block.dataSize)
                RarArchiveInfo(
                    solid = main.solid,
                    multiVolume = main.volume,
                    volumeNumber = main.volumeNumber,
                    headerEncrypted = headerEncrypted,
                    locked = main.locked,
                    hasRecoveryRecord = main.recovery,
                )
            }
            else -> {
                // libarchive accepts archives without a main header; so do we, with default flags.
                pending = block
                RarArchiveInfo(
                    solid = false,
                    multiVolume = false,
                    volumeNumber = 0,
                    headerEncrypted = headerEncrypted,
                    locked = false,
                    hasRecoveryRecord = false,
                )
            }
        }
    }

    private fun readNextEntry(): RarEntry? {
        while (true) {
            val block = pending?.also { pending = null }
                ?: readBlock()
                ?: throw RarCorruptException("Unexpected end of archive before the end-of-archive header")
            when (block.type) {
                HeaderType.FILE -> return startEntry(FileHeader.parse(block, service = false, limits = limits))
                HeaderType.ENDARC -> {
                    // Like libarchive, only an archive whose main header marks a volume continues.
                    if (!EndHeader.parse(block).moreVolumes || !archive.multiVolume) {
                        finished = true
                        return null
                    }
                    nextVolume()
                }
                else -> skipOther(block)
            }
        }
    }

    /** Skips a block that neither starts an entry nor ends the volume. */
    private fun skipOther(block: Block) {
        when (block.type) {
            HeaderType.SERVICE -> input.skipFully(FileHeader.parse(block, service = true, limits = limits).dataSize)
            HeaderType.MAIN -> input.skipFully(block.dataSize)
            HeaderType.CRYPT -> throw RarCorruptException("Archive encryption header after the start of the archive")
            HeaderType.MARK -> throw RarCorruptException("Unexpected marker header")
            else -> {
                if (block.flags and HeaderFlags.SKIP_IF_UNKNOWN == 0L) {
                    throw RarCorruptException("Unknown header type ${block.type}")
                }
                input.skipFully(block.dataSize)
            }
        }
    }

    /**
     * Continues with the volume after the current one, at an end header that announced it: closes
     * the current volume, opens the next and reads it up to its main header.
     */
    private fun nextVolume() {
        val index = volumeIndex + 1
        if (index >= limits.maxVolumes) {
            throw RarUnsupportedException(RarUnsupportedReason.LimitExceeded("maxVolumes", index + 1L, limits.maxVolumes.toLong()))
        }
        input.close()
        options.cancellation()
        val stream = options.volumeProvider?.open(index) ?: throw RarMissingVolumeException(index)
        input.attach(stream)
        volumeIndex = index
        startVolume(index)
    }

    /**
     * Reads volume [index] up to and including its main header. RAR5 volumes carry no set
     * identifier, so only the signature, the header encryption and the volume number are checked.
     */
    private fun startVolume(index: Int) {
        val signature = ByteArray(RarSignature.RAR5_SIZE)
        val length = input.readUpTo(signature, 0, signature.size)
        if (RarSignature.detect(signature, length) != RarSignature.Kind.RAR5) throw RarCorruptException("Volume $index is not a RAR5 archive")
        val keys = headerKeys
        val first = readBlock(plain = true) ?: throw RarCorruptException("Volume $index has no main header")
        val block = if (first.type == HeaderType.CRYPT) {
            if (keys == null) throw RarCorruptException("Volume $index has encrypted headers, the first volume plain ones")
            continueHeaderDecryption(keys, CryptHeader.parse(first))
            readBlock() ?: throw RarCorruptException("Volume $index has no main header")
        } else {
            if (keys != null) throw RarCorruptException("Volume $index has plain headers, the first volume encrypted ones")
            first
        }
        if (block.type != HeaderType.MAIN) throw RarCorruptException("Volume $index has no main header")
        val main = MainHeader.parse(block, limits)
        if (!main.volume || main.volumeNumber != index) {
            val declared = if (main.volume) "volume number ${main.volumeNumber}" else "no volume"
            throw RarCorruptException("Volume $index declares $declared")
        }
        input.skipFully(block.dataSize)
    }

    /**
     * Keeps the header keys for a later volume whose encryption header accepts them, as the entry
     * key cache does; otherwise asks for the HEADERS password again.
     */
    private fun continueHeaderDecryption(keys: Rar5Keys, crypt: CryptHeader) {
        val previous = headerCrypt
        if (previous != null && crypt.algorithm == 0L && crypt.kdfCount == previous.kdfCount && crypt.salt.contentEquals(previous.salt)) {
            when (Rar5Crypto.check(keys, crypt.check)) {
                Rar5PasswordCheck.MATCH -> {
                    headerProven = true
                    return
                }
                Rar5PasswordCheck.ABSENT -> return
                Rar5PasswordCheck.MISMATCH -> Unit
            }
        }
        headerKeys = null
        keys.wipe()
        headerProven = false
        headerVerified = false
        startHeaderDecryption(crypt)
    }

    /**
     * Moves [cur] to its next part: past the rest of the current volume to its end header, then
     * into the next volume up to the header that continues the entry.
     */
    private fun continuePart(cur: Current) {
        val entry = cur.entry
        while (true) {
            val block = readBlock() ?: throw RarCorruptException("Volume $volumeIndex ends without an end header")
            when (block.type) {
                HeaderType.ENDARC -> {
                    if (!EndHeader.parse(block).moreVolumes || !archive.multiVolume) {
                        throw RarCorruptException("Entry ${entry.ordinal} (${entry.name}) continues after the last volume")
                    }
                    break
                }
                HeaderType.FILE -> throw RarCorruptException("Entry ${entry.ordinal} (${entry.name}) is not continued after volume $volumeIndex")
                else -> skipOther(block)
            }
        }
        nextVolume()
        while (true) {
            val block = readBlock() ?: throw RarCorruptException("Volume $volumeIndex ends before it continues entry ${entry.ordinal} (${entry.name})")
            when (block.type) {
                HeaderType.FILE -> {
                    val next = FileHeader.parse(block, service = false, limits = limits)
                    checkContinuation(cur, next)
                    cur.startPart(next)
                    return
                }
                HeaderType.ENDARC -> throw RarCorruptException("Volume $volumeIndex does not continue entry ${entry.ordinal} (${entry.name})")
                else -> skipOther(block)
            }
        }
    }

    /** 7-Zip's CItem::IsNextForItem, plus the size, checksum types and encryption a continued entry keeps. */
    private fun checkContinuation(cur: Current, next: FileHeader) {
        val previous = cur.part
        val mismatch = when {
            !next.splitBefore -> "does not continue"
            next.name != previous.name || next.isDirectory || previous.isDirectory -> "continues another entry than"
            next.size != previous.size -> "declares another size for"
            (next.crc32 != null) != (previous.crc32 != null) || (next.blake2sp != null) != (previous.blake2sp != null) -> "declares other checksums for"
            !sameCrypt(next.crypt, previous.crypt) -> "declares other encryption for"
            else -> return
        }
        throw RarCorruptException("Volume $volumeIndex $mismatch entry ${cur.entry.ordinal} (${cur.entry.name})")
    }

    private fun sameCrypt(a: FileCrypt?, b: FileCrypt?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.algorithm == b.algorithm && a.flags == b.flags && a.kdfCount == b.kdfCount &&
            a.salt.contentEquals(b.salt) && a.iv.contentEquals(b.iv) && a.check.contentEquals(b.check)
    }

    /**
     * Up to [length] of [cur]'s packed bytes, continuing in the next volume after a part that is
     * split after; -1 after the last part. A part read completely through here has its checksum
     * verified while [Current.verifyParts] holds. Every failure here concerns the archive, never the
     * entry's password.
     */
    private fun readPacked(cur: Current, buffer: ByteArray, offset: Int, length: Int): Int = try {
        while (cur.remaining <= 0L && cur.part.splitAfter) continuePart(cur)
        if (cur.remaining <= 0L) {
            -1
        } else {
            val n = input.read(buffer, offset, minOf(length.toLong(), cur.remaining).toInt())
            if (n < 0) throw RarCorruptException("Volume $volumeIndex ends inside entry ${cur.entry.ordinal} (${cur.entry.name})")
            cur.remaining -= n
            if (cur.verifyParts) {
                cur.partCrc?.update(buffer, offset, n)
                cur.partBlake?.update(buffer, offset, n)
                if (cur.remaining == 0L) verifyPart(cur)
            }
            n
        }
    } catch (t: Throwable) {
        cur.inputFailed = true
        throw t
    }

    /** Checks the stored checksum of a part that is split after against its packed bytes, without MAC. */
    private fun verifyPart(cur: Current) {
        val part = cur.part
        val crc = cur.partCrc
        val blake = cur.partBlake
        cur.stopPartHash()
        val kind = when {
            crc != null && crc.value.toInt() != part.crc32 -> "CRC32"
            blake != null && !blake.digest().contentEquals(part.blake2sp) -> "BLAKE2sp"
            else -> return
        }
        throw RarCorruptException("$kind mismatch for the part of entry ${cur.entry.ordinal} (${cur.entry.name}) in volume $volumeIndex")
    }

    /** Skips the rest of [cur]'s packed data through all of its later parts, without verifying them. */
    private fun skipPacked(cur: Current) {
        while (true) {
            cur.stopPartHash()
            input.skipFully(cur.remaining)
            cur.remaining = 0
            if (!cur.part.splitAfter) return
            continuePart(cur)
        }
    }

    /** Reads and discards the rest of [cur]'s packed data, verifying its later parts; returns the byte count. */
    private fun drainPacked(cur: Current): Long {
        var total = 0L
        while (true) {
            val n = readPacked(cur, discard, 0, discard.size)
            if (n < 0) return total
            total += n
        }
    }

    private fun startEntry(header: FileHeader): RarEntry {
        if (header.splitBefore) throw RarCorruptException("Continued file header in volume $volumeIndex without the entry it continues")
        if (nextOrdinal >= limits.maxEntries) {
            throw RarUnsupportedException(
                RarUnsupportedReason.LimitExceeded("maxEntries", nextOrdinal.toLong() + 1, limits.maxEntries.toLong()),
            )
        }
        val entry = RarEntry(
            ordinal = nextOrdinal++,
            name = header.name,
            isDirectory = header.isDirectory,
            size = header.size,
            packedSize = header.dataSize,
            mtime = header.mtime,
            ctime = header.ctime,
            atime = header.atime,
            attributes = header.attributes,
            hostOs = header.hostOs,
            isEncrypted = header.crypt != null,
            isSolid = header.compression.solid,
            redirection = header.redirection,
            checksum = if (header.splitAfter) null else header.checksum,
            version = header.version,
            splitAcrossVolumes = header.splitAfter,
        )
        current = Current(header, entry)
        return entry
    }

    private fun startHeaderDecryption(crypt: CryptHeader) {
        if (crypt.algorithm != 0L) throw RarUnsupportedException(RarUnsupportedReason.UnsupportedEncryption(crypt.algorithm))
        kdfLimit(crypt.kdfCount)?.let { throw it }
        headerCrypt = crypt
        val keys = derive(RarPasswordRequest(RarPasswordScope.HEADERS, null, null), checkNotNull(crypt.salt), crypt.kdfCount)
            ?: throw RarPasswordRequiredException(RarPasswordScope.HEADERS, null, null)
        headerKeys = keys
        when (Rar5Crypto.check(keys, crypt.check)) {
            Rar5PasswordCheck.MISMATCH -> throw RarWrongPasswordException(RarPasswordScope.HEADERS, null, null, certain = true)
            Rar5PasswordCheck.MATCH -> headerProven = true
            Rar5PasswordCheck.ABSENT -> Unit
        }
    }

    /**
     * One base block (CRC, size, header), or null at a clean end of the stream. [plain] reads a
     * volume's archive encryption header, which precedes its encrypted headers.
     */
    private fun readBlock(plain: Boolean = false): Block? {
        if (!plain) headerKeys?.let { return readEncryptedBlock(it) }
        val crcBytes = ByteArray(4)
        val crcLength = input.readUpTo(crcBytes, 0, 4)
        if (crcLength == 0) return null
        if (crcLength < 4) throw ArchiveInput.unexpectedEnd()
        val expectedCrc = (crcBytes[0].toInt() and 0xFF) or
            ((crcBytes[1].toInt() and 0xFF) shl 8) or
            ((crcBytes[2].toInt() and 0xFF) shl 16) or
            ((crcBytes[3].toInt() and 0xFF) shl 24)

        // The size field has at most 3 bytes, which bounds a header to 2 MiB.
        val sizeBytes = ByteArray(3)
        var size = 0
        var sizeLength = 0
        while (true) {
            if (sizeLength == sizeBytes.size) throw RarCorruptException("Header size field too long")
            input.readFully(sizeBytes, sizeLength, 1)
            val b = sizeBytes[sizeLength].toInt() and 0xFF
            size = size or ((b and 0x7F) shl (7 * sizeLength))
            sizeLength++
            if (b and 0x80 == 0) break
        }
        if (size < 2) throw RarCorruptException("Header too small ($size bytes)")
        if (size > limits.maxHeaderSize) {
            throw RarUnsupportedException(
                RarUnsupportedReason.LimitExceeded("maxHeaderSize", size.toLong(), limits.maxHeaderSize.toLong()),
            )
        }
        val data = ByteArray(sizeLength + size)
        sizeBytes.copyInto(data, 0, 0, sizeLength)
        input.readFully(data, sizeLength, size)
        if (Block.crcOf(data) != expectedCrc) throw RarCorruptException("Header CRC mismatch")
        return Block.parse(data, sizeLength)
    }

    /**
     * One base block behind its own 16-byte IV, AES-256-CBC encrypted and padded to whole AES
     * blocks, or null at a clean end of the stream. Data areas that follow headers are not covered.
     */
    private fun readEncryptedBlock(keys: Rar5Keys): Block? {
        val aes = Rar5Crypto.BLOCK_SIZE
        val head = ByteArray(2 * aes)
        val headLength = input.readUpTo(head, 0, head.size)
        if (headLength == 0) return null
        if (headLength < head.size) throw ArchiveInput.unexpectedEnd()
        val cbc = Rar5Cbc(keys.aesKey, head.copyOfRange(0, aes))
        cbc.decrypt(head, aes, aes)

        // The first AES block holds the CRC and the whole size field (at most 3 bytes).
        var size = 0
        var sizeLength = 0
        while (true) {
            if (sizeLength == 3) throw headerFailure(RarCorruptException("Header size field too long"))
            val b = head[aes + 4 + sizeLength].toInt() and 0xFF
            size = size or ((b and 0x7F) shl (7 * sizeLength))
            sizeLength++
            if (b and 0x80 == 0) break
        }
        if (size < 2) throw headerFailure(RarCorruptException("Header too small ($size bytes)"))
        if (size > limits.maxHeaderSize) {
            throw headerFailure(
                RarUnsupportedException(RarUnsupportedReason.LimitExceeded("maxHeaderSize", size.toLong(), limits.maxHeaderSize.toLong())),
            )
        }
        val total = 4 + sizeLength + size
        val padded = (total + aes - 1) / aes * aes
        val plain = ByteArray(padded)
        head.copyInto(plain, 0, aes, 2 * aes)
        if (padded > aes) {
            if (input.readUpTo(plain, aes, padded - aes) != padded - aes) throw headerFailure(ArchiveInput.unexpectedEnd())
            cbc.decrypt(plain, aes, padded - aes)
        }
        val expectedCrc = (plain[0].toInt() and 0xFF) or
            ((plain[1].toInt() and 0xFF) shl 8) or
            ((plain[2].toInt() and 0xFF) shl 16) or
            ((plain[3].toInt() and 0xFF) shl 24)
        val actualCrc = CRC32().apply { update(plain, 4, total - 4) }.value.toInt()
        if (actualCrc != expectedCrc) throw headerFailure(RarCorruptException("Header CRC mismatch"))
        headerVerified = true
        return Block.parse(plain.copyOfRange(4, total), sizeLength)
    }

    /** Until a header decrypted cleanly, a password the archive could not check is the likely cause of [e]. */
    private fun headerFailure(e: RarException): RarException {
        if (headerProven || headerVerified) return e
        return RarWrongPasswordException(RarPasswordScope.HEADERS, null, null, certain = false).apply { addSuppressed(e) }
    }

    /**
     * Keys for encrypted entry [cur], from the cache or from the provider. Files may share a salt but
     * not a password, so a cached key is only reused when the entry's check value does not reject it.
     */
    private fun entryKeys(cur: Current): KeyOutcome {
        val crypt = checkNotNull(cur.header.crypt)
        val entry = cur.entry
        kdfLimit(crypt.kdfCount)?.let { e -> return KeyOutcome.Unavailable { unsupported(entry, e.reason) } }
        val salt = checkNotNull(crypt.salt)
        for (cached in keyCache.reversed()) {
            if (cached.kdfCount != crypt.kdfCount || !cached.salt.contentEquals(salt)) continue
            val check = Rar5Crypto.check(cached.keys, crypt.check)
            if (check == Rar5PasswordCheck.MISMATCH) continue
            keyCache.remove(cached)
            keyCache.addLast(cached)
            return KeyOutcome.Ready(cached.keys, proven = check == Rar5PasswordCheck.MATCH)
        }
        val keys = derive(RarPasswordRequest(RarPasswordScope.ENTRY, entry.ordinal, entry.name), salt, crypt.kdfCount)
            ?: return KeyOutcome.Unavailable { RarPasswordRequiredException(RarPasswordScope.ENTRY, entry.ordinal, entry.name) }
        val check = Rar5Crypto.check(keys, crypt.check)
        if (check == Rar5PasswordCheck.MISMATCH) {
            keys.wipe()
            return KeyOutcome.Unavailable { RarWrongPasswordException(RarPasswordScope.ENTRY, entry.ordinal, entry.name, certain = true) }
        }
        if (keyCache.size == KEY_CACHE_SIZE) keyCache.removeFirst().keys.wipe()
        keyCache.addLast(CachedKeys(salt, crypt.kdfCount, keys))
        return KeyOutcome.Ready(keys, proven = check == Rar5PasswordCheck.MATCH)
    }

    /** Asks for [request]'s password and derives its keys; null without a password. The password is wiped. */
    private fun derive(request: RarPasswordRequest, salt: ByteArray, kdfCount: Int): Rar5Keys? {
        val provider = options.passwordProvider ?: return null
        val chars = provider.password(request) ?: return null
        val password = try {
            Rar5Crypto.passwordBytes(chars)
        } finally {
            chars.fill('\u0000')
        }
        return try {
            keyDerivation.derive(password, salt, kdfCount, options.cancellation)
        } finally {
            password.fill(0)
        }
    }

    private fun kdfLimit(kdfCount: Int): RarUnsupportedException? {
        val max = minOf(limits.maxKdfLog2, Rar5Crypto.MAX_LOG2_COUNT)
        if (kdfCount <= max) return null
        return RarUnsupportedException(RarUnsupportedReason.LimitExceeded("maxKdfLog2", kdfCount.toLong(), max.toLong()))
    }

    /** For [cur] decrypted with a password its check value could not prove: the likely reason its data failed. */
    private fun unprovenFailure(cur: Current): (() -> RarException)? {
        val keys = cur.keys ?: return null
        if (cur.proven || cur.inputFailed) return null
        // The key is suspect, so later entries with the same salt ask again.
        keyCache.firstOrNull { it.keys === keys }?.let { keyCache.remove(it) }
        keys.wipe()
        val entry = cur.entry
        return { RarWrongPasswordException(RarPasswordScope.ENTRY, entry.ordinal, entry.name, certain = false) }
    }

    private fun checkEncryptedLayout(cur: Current) {
        val header = cur.header
        val entry = cur.entry
        // The parts of a split entry are decrypted as one stream; CryptSource and EntryStream.verify() check their total.
        if (header.splitAfter) return
        if (header.dataSize % Rar5Crypto.BLOCK_SIZE != 0L) {
            throw RarCorruptException("Encrypted entry ${entry.ordinal} (${entry.name}) has packed size ${header.dataSize}, not whole AES blocks")
        }
        if (!header.isLz && header.size != null && header.size > header.dataSize) {
            throw RarCorruptException("Stored entry ${entry.ordinal} has packed size ${header.dataSize} but size ${header.size}")
        }
    }

    private fun wipeKeys() {
        headerKeys?.wipe()
        keyCache.forEach { it.keys.wipe() }
        keyCache.clear()
    }

    private fun checkUsable() {
        if (closed) throw IOException("RarReader is closed")
        failure?.let { throw IOException("RarReader failed earlier: ${it.message}", it) }
    }

    /**
     * Why [cur]'s data cannot be decoded at all, or null. The dictionary check applies to an entry
     * that starts a stream with a known size; streams of unknown length fail when they grow too far.
     */
    private fun entryBlocker(cur: Current): (() -> RarException)? {
        val header = cur.header
        val entry = cur.entry
        val compression = header.compression
        val crypt = header.crypt
        if (crypt != null && crypt.algorithm != 0L) return { unsupported(entry, RarUnsupportedReason.UnsupportedEncryption(crypt.algorithm)) }
        if (!compression.supportedVersion) {
            return { unsupported(entry, RarUnsupportedReason.AlgorithmVersion(compression.algorithmVersion)) }
        }
        if (compression.method == 0) return null
        if (compression.method > MAX_METHOD) return { unsupported(entry, RarUnsupportedReason.UnknownMethod(compression.method)) }
        val size = header.size
        if (!compression.solid && size != null) {
            val required = minOf(checkNotNull(compression.windowBytes), size)
            if (required > limits.maxWindowBytes) {
                return { unsupported(entry, RarUnsupportedReason.DictionaryTooLarge(required, limits.maxWindowBytes)) }
            }
        }
        return null
    }

    /** Names [entry], so a later solid entry blocked by it reports which entry that was. */
    private fun unsupported(entry: RarEntry, reason: RarUnsupportedReason) =
        RarUnsupportedException(reason, "Entry ${entry.ordinal} (${entry.name}): $reason")

    /**
     * Feeds [cur]'s packed data to the decoder: a solid entry continues the current stream, any
     * other entry releases it and reserves the window of a new one before decoding starts.
     */
    private fun beginDecode(cur: Current): Rar5Decoder {
        val header = cur.header
        val compression = header.compression
        val window = checkNotNull(compression.windowBytes)
        val stream = if (compression.solid) {
            val previous = decoder
                ?: throw RarCorruptException("Solid entry ${cur.entry.ordinal} (${cur.entry.name}) does not continue a compressed entry")
            if (previous.window.size != window) {
                throw RarCorruptException(
                    "Solid entry ${cur.entry.ordinal} declares a $window-byte dictionary, its stream uses ${previous.window.size}",
                )
            }
            previous
        } else {
            releaseStream()
            // Later solid entries can only grow a stream that started with enough reserved.
            val size = header.size
            val need = if (size != null && !archive.solid) minOf(window, size) else minOf(window, limits.maxWindowBytes)
            val chunk = Rar5Window.chunkFor(window, windowChunkSize)
            val reservation = Rar5Window.storageFor(need, window, chunk)
            if (reservation > 0) {
                options.memoryBudget.acquire(reservation)
                reserved = reservation
            }
            Rar5Decoder(window, chunk, reservation, limits, options.cancellation, filterCounts).also { decoder = it }
        }
        stream.startEntry(if (header.splitAfter) null else header.dataSize, header.size, packedSource(cur))
        cur.decodeStarted = true
        return stream
    }

    private fun packedSource(cur: Current): PackedSource =
        if (cur.keys != null) CryptSource(cur) else PackedSource { buffer, offset, length -> readPacked(cur, buffer, offset, length) }

    /** Leaves [prev] behind: its unread data is decoded where a solid stream needs it, else skipped. */
    private fun finishEntry(prev: Current) {
        if (prev.header.isLz && !prev.decoded && !prev.abandoned && !options.listOnly) {
            if (archive.solid) skipDecode(prev) else releaseStream()
        }
        skipPacked(prev)
    }

    /** Decodes and discards the rest of [prev], without checksums; an undecodable entry blocks the stream. */
    private fun skipDecode(prev: Current) {
        prev.verifyParts = false
        prev.stopPartHash()
        if (!prev.decodeStarted) {
            if (prev.header.compression.solid && blocker != null) return
            val reason = entryBlocker(prev)
            if (reason != null) {
                blockStream(reason)
                return
            }
            if (prev.header.crypt != null) {
                checkEncryptedLayout(prev)
                when (val outcome = entryKeys(prev)) {
                    is KeyOutcome.Ready -> prev.useKeys(outcome)
                    is KeyOutcome.Unavailable -> {
                        blockStream(outcome.reason)
                        return
                    }
                }
            }
            beginDecode(prev)
        }
        val stream = decoder ?: return
        try {
            while (stream.read(discard, 0, discard.size) >= 0) Unit
            prev.decoded = true
        } catch (e: RarUnsupportedException) {
            // Thrown by the caller's own stream: it poisons the reader like any other stream failure.
            if (prev.inputFailed) throw e
            val entry = prev.entry
            when (val reason = e.reason) {
                is RarUnsupportedReason.DictionaryTooLarge -> blockStream { unsupported(entry, reason) }
                is RarUnsupportedReason.UnknownFilter -> blockStream(unprovenFailure(prev) ?: unknownFilter(prev, reason))
                else -> throw e
            }
        } catch (e: RarCorruptException) {
            blockStream(unprovenFailure(prev) ?: throw e)
        }
    }

    /** Blocks the solid stream after [cur], whose data uses a filter type this reader does not know. */
    private fun unknownFilter(cur: Current, reason: RarUnsupportedReason.UnknownFilter): () -> RarException {
        val entry = cur.entry
        return { RarUnsupportedException(reason, "Entry ${entry.ordinal} (${entry.name}) uses unsupported filter type ${reason.type}") }
    }

    private fun blockStream(reason: () -> RarException) {
        releaseStream()
        blocker = reason
    }

    private fun releaseStream() {
        decoder = null
        blocker = null
        val held = reserved
        if (held > 0) {
            reserved = 0
            options.memoryBudget.release(held)
        }
    }

    private fun poison(t: Throwable): Throwable {
        if (failure == null) {
            failure = t
            wipeKeys()
            try {
                releaseStream()
            } catch (releaseFailure: Throwable) {
                if (releaseFailure !== t) t.addSuppressed(releaseFailure)
            }
        }
        return t
    }

    private inline fun <T> poisonOnFailure(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw poison(t)
    }

    private class Current(val header: FileHeader, val entry: RarEntry) {
        /** The part being read: [header] until a split entry continues in a later volume. */
        var part: FileHeader = header
            private set

        /** Packed bytes of [part] not yet read or skipped. */
        var remaining = 0L

        /** Hash [part]'s packed bytes while all of them pass through readPacked; null otherwise. */
        var partCrc: CRC32? = null
            private set
        var partBlake: Blake2sp? = null
            private set

        /** Part checksums are checked; data that is only decoded to be skipped is not. */
        var verifyParts = true

        /** Packed bytes of all parts started so far. */
        var packedTotal = 0L
            private set

        init {
            startPart(header)
        }

        /** A part that another follows stores the checksum of its own packed bytes (7-Zip CVolsInStream::Read). */
        fun startPart(next: FileHeader) {
            part = next
            remaining = next.dataSize
            packedTotal += next.dataSize
            partCrc = if (verifyParts && next.splitAfter && next.crc32 != null) CRC32() else null
            partBlake = if (verifyParts && next.splitAfter && next.blake2sp != null) Blake2sp() else null
        }

        fun stopPartHash() {
            partCrc = null
            partBlake = null
        }

        var streamRequested = false

        /** Keys decrypting this entry; [proven] when its check value matched them. */
        var keys: Rar5Keys? = null
        var proven = false

        /** Reading the packed data failed (end of a volume, a part checksum, a continuation), which no password explains. */
        var inputFailed = false

        fun useKeys(ready: KeyOutcome.Ready) {
            keys = ready.keys
            proven = ready.proven
        }

        /** The entry's packed data is being fed to the decoder. */
        var decodeStarted = false

        /** The decoder delivered all of the entry's data, so a following solid entry can continue. */
        var decoded = false

        /** Decoding stopped for a reason that concerns this entry only; the stream is blocked. */
        var abandoned = false
    }

    private class CachedKeys(val salt: ByteArray, val kdfCount: Int, val keys: Rar5Keys)

    private sealed interface KeyOutcome {
        class Ready(val keys: Rar5Keys, val proven: Boolean) : KeyOutcome

        class Unavailable(val reason: () -> RarException) : KeyOutcome
    }

    /**
     * [cur]'s packed data decrypted with its keys and IV, as one CBC chain across the parts of a
     * split entry (7-Zip decrypts the joined parts); the packed total is whole AES blocks.
     */
    private inner class CryptSource(private val cur: Current) : PackedSource {
        private val cbc = Rar5Cbc(checkNotNull(cur.keys).aesKey, checkNotNull(cur.header.crypt?.iv))
        private val plain = ByteArray(CRYPT_BUFFER_SIZE)
        private var pos = 0
        private var end = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos == end) {
                var n = 0
                while (n < plain.size) {
                    val r = readPacked(cur, plain, n, plain.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) return -1
                if (n % Rar5Crypto.BLOCK_SIZE != 0) {
                    cur.inputFailed = true
                    throw RarCorruptException("Encrypted entry ${cur.entry.ordinal} (${cur.entry.name}) has packed data that is not whole AES blocks")
                }
                cbc.decrypt(plain, 0, n)
                pos = 0
                end = n
            }
            val n = minOf(length, end - pos)
            plain.copyInto(buffer, offset, pos, pos + n)
            pos += n
            return n
        }
    }

    /** Stored data from [source] when [lz] is false, decoder output otherwise. [length] null means unknown. */
    private inner class EntryStream(
        private val owner: Current,
        private val length: Long?,
        private val lz: Boolean,
        private val checked: Boolean = true,
        private val source: PackedSource? = null,
    ) : InputStream() {
        private val streamGeneration = generation
        private var delivered = 0L
        private val crc = CRC32()
        private val blake = if (checked && owner.header.blake2sp != null) Blake2sp() else null
        private var done = length == 0L
        private var verified = false
        private var streamClosed = false
        private var abandonedWith: RarException? = null

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkValid()
            abandonedWith?.let { throw IOException("Entry data failed earlier: ${it.message}", it) }
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            if (len == 0) return 0
            if (done) {
                verify()
                return -1
            }
            val n = if (lz) readDecoded(b, off, len) else readStored(b, off, len)
            if (n < 0) {
                done = true
                owner.decoded = true
                verify()
                return -1
            }
            crc.update(b, off, n)
            blake?.update(b, off, n)
            delivered += n
            if (delivered == length) {
                done = true
                owner.decoded = true
                verify()
            }
            return n
        }

        /** -1 only at the end of data of unknown [length]. */
        private fun readStored(b: ByteArray, off: Int, len: Int): Int {
            val want = if (length != null) minOf(len.toLong(), length - delivered).toInt() else len
            return poisonOnFailure {
                val n = checkNotNull(source).read(b, off, want)
                if (n < 0 && length != null) throw ArchiveInput.unexpectedEnd()
                n
            }
        }

        private fun readDecoded(b: ByteArray, off: Int, len: Int): Int {
            val stream = decoder ?: throw IOException("Entry stream is no longer valid")
            return try {
                stream.read(b, off, len)
            } catch (e: RarUnsupportedException) {
                // Thrown by the caller's own stream, not by the decoder.
                if (owner.inputFailed) throw poison(e)
                when (val reason = e.reason) {
                    // The window outgrew the limit: this entry and its solid successors are lost, the reader is not.
                    is RarUnsupportedReason.DictionaryTooLarge -> throw abandon(e) { unsupported(owner.entry, reason) }
                    // Garbage from an unproven password may read as any filter type.
                    is RarUnsupportedReason.UnknownFilter -> {
                        val blocked = unprovenFailure(owner) ?: throw abandon(e, unknownFilter(owner, reason))
                        throw abandon(blocked().apply { addSuppressed(e) }, blocked)
                    }
                    else -> throw poison(e)
                }
            } catch (e: RarCorruptException) {
                val reason = unprovenFailure(owner) ?: throw poison(e)
                throw abandon(reason().apply { addSuppressed(e) }, reason)
            } catch (t: Throwable) {
                throw poison(t)
            }
        }

        /** Gives up on this entry for a reason that concerns it alone; a solid stream cannot continue past it. */
        private fun abandon(thrown: RarException, reason: () -> RarException): RarException {
            abandonedWith = thrown
            owner.abandoned = true
            if (lz) poisonOnFailure { blockStream(reason) }
            return thrown
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val buffer = ByteArray(minOf(n, 8192L).toInt())
            var skipped = 0L
            while (skipped < n) {
                val r = read(buffer, 0, minOf(n - skipped, buffer.size.toLong()).toInt())
                if (r < 0) break
                skipped += r
            }
            return skipped
        }

        override fun available(): Int {
            checkValid()
            return 0
        }

        override fun close() {
            streamClosed = true
        }

        private fun checkValid() {
            if (streamClosed) throw IOException("Entry stream is closed")
            if (closed || generation != streamGeneration) throw IOException("Entry stream is no longer valid")
            failure?.let { throw IOException("RarReader failed earlier: ${it.message}", it) }
        }

        /**
         * With the MAC flag, the stored checksums are HMACs under the entry's hash key (7-Zip
         * CHash::Check). A split entry's are in its last part, so its remaining parts are read first.
         */
        private fun verify() {
            if (verified || !checked) return
            verified = true
            if (owner.header.splitAfter) {
                val rest = poisonOnFailure { drainPacked(owner) }
                if (!lz && owner.header.crypt == null && rest > 0) {
                    throw poison(RarCorruptException("Stored entry ${owner.entry.ordinal} (${owner.entry.name}) has more packed data than its size"))
                }
                val total = owner.packedTotal
                if (owner.header.crypt != null && total % Rar5Crypto.BLOCK_SIZE != 0L) {
                    throw poison(RarCorruptException("Encrypted entry ${owner.entry.ordinal} (${owner.entry.name}) has packed size $total, not whole AES blocks"))
                }
            }
            val part = owner.part
            val macKey = owner.keys?.takeIf { part.crypt?.usesMac == true }?.hashKey
            val expectedCrc = part.crc32
            if (expectedCrc != null) {
                val actual = crc.value.toInt().let { if (macKey != null) Rar5Crypto.macCrc32(macKey, it) else it }
                if (actual != expectedCrc) throw checksumFailure("CRC32")
            }
            val expectedBlake = part.blake2sp
            if (expectedBlake != null && blake != null) {
                val actual = blake.digest().let { if (macKey != null) Rar5Crypto.macDigest(macKey, it) else it }
                if (!actual.contentEquals(expectedBlake)) throw checksumFailure("BLAKE2sp")
            }
        }

        private fun checksumFailure(kind: String): Throwable {
            val corrupt = RarCorruptException("$kind mismatch for entry ${owner.entry.ordinal} (${owner.entry.name})")
            val reason = unprovenFailure(owner) ?: return poison(corrupt)
            return abandon(reason().apply { addSuppressed(corrupt) }, reason)
        }
    }

    private companion object {
        /** Methods 1 to 5 are the LZ compression levels; 0 is stored. */
        const val MAX_METHOD = 5
        const val DISCARD_SIZE = 64 * 1024
        const val CRYPT_BUFFER_SIZE = 16 * 1024

        /** Distinct entry keys kept; RAR usually derives one salt per archive or per password. */
        const val KEY_CACHE_SIZE = 4
    }
}

/** Entry data that goes through the LZ decoder; unsupported versions count, their data is never stored. */
private val FileHeader.isLz: Boolean
    get() = !isDirectory && redirection == null && (compression.method != 0 || !compression.supportedVersion)
