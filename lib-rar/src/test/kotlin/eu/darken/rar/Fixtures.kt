package eu.darken.rar

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Fixture bytes and manifests under src/test/resources/fixtures (see the README there). */
internal object Fixtures {
    fun bytes(path: String): ByteArray =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$path")) { "Missing fixture $path" }
            .use { it.readAllBytes() }

    fun stream(path: String): InputStream = ByteArrayInputStream(bytes(path))

    val rar723: Manifest by lazy { Manifest.load("rar723") }

    @Suppress("UNCHECKED_CAST")
    val libarchive: Map<String, LibarchiveFixture> by lazy {
        val root = TestJson.parse(String(bytes("libarchive/manifest.json"), Charsets.UTF_8)) as Map<String, Any?>
        val fixtures = root["fixtures"] as Map<String, Map<String, Any?>>
        fixtures.mapValues { (name, f) -> LibarchiveFixture.from(name, f) }
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Reads every entry; returns them in archive order. */
    fun listAll(reader: RarReader): List<RarEntry> = generateSequence { reader.nextEntry() }.toList()

    fun list(path: String, options: RarOptions = RarOptions()): Pair<RarArchiveInfo, List<RarEntry>> =
        RarReader(stream(path), options).use { it.archive to listAll(it) }

    /** Resource paths of the volume set whose first volume is [firstPath], named by [RarVolumeNames]. */
    fun volumeSet(firstPath: String): List<String> {
        val directory = firstPath.substringBeforeLast('/')
        val first = firstPath.substringAfterLast('/')
        return generateSequence(0) { it + 1 }
            .map { index -> RarVolumeNames.volumeName(first, index)?.let { "$directory/$it" } }
            .takeWhile { it != null && Fixtures::class.java.getResource("/fixtures/$it") != null }
            .map { it!! }
            .toList()
    }
}

/**
 * Serves volume `k` of a set from [volumes] (index 0 is the first volume), null past its end. Records
 * every requested index and every stream it handed out.
 */
internal class TestVolumes(private val volumes: (Int) -> ByteArray?) : RarVolumeProvider {
    constructor(list: List<ByteArray>) : this({ list.getOrNull(it) })

    val opened = mutableListOf<Int>()
    val streams = mutableListOf<TrackingInputStream>()

    val allClosed: Boolean get() = streams.all { it.closed }

    override fun open(volumeIndex: Int): InputStream? {
        opened += volumeIndex
        val bytes = volumes(volumeIndex) ?: return null
        return TrackingInputStream(ByteArrayInputStream(bytes)).also { streams += it }
    }

    /** Volume 0, to hand to the reader; recorded like the others. */
    fun first(): InputStream = checkNotNull(open(0)) { "No first volume" }

    companion object {
        /** The fixture set whose first volume is [firstPath]. */
        fun fixture(firstPath: String): TestVolumes = TestVolumes(Fixtures.volumeSet(firstPath).map { Fixtures.bytes(it) })
    }
}

@Suppress("UNCHECKED_CAST")
internal class Manifest(val directory: String, val fixtures: Map<String, ManifestFixture>) {
    /** Resource path of a [ManifestFixture.files] entry, e.g. "stored.rar" -> "rar723/stored.rar". */
    fun path(file: String): String = "$directory/$file"

    companion object {
        fun load(directory: String): Manifest {
            val root = TestJson.parse(String(Fixtures.bytes("$directory/manifest.json"), Charsets.UTF_8)) as Map<String, Any?>
            val fixtures = (root["fixtures"] as Map<String, Map<String, Any?>>)
                .mapValues { (name, f) -> ManifestFixture.from(name, f) }
            return Manifest(directory, fixtures)
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal data class ManifestFixture(
    val name: String,
    val files: List<String>,
    val listPassword: String?,
    val headerEncrypted: Boolean,
    val solid: Boolean,
    val details: String,
    val entries: List<ManifestEntry>,
    /** Filter types libarchive's bsdtar parsed while extracting (0 delta, 1 E8, 2 E8E9, 3 ARM), null if not scanned. */
    val filterTypes: List<Int>?,
    val filterCount: Int?,
) {
    val isVolumeSet: Boolean get() = files.size > 1

    /** Entries whose header is in the first volume, continuation headers excluded. */
    val firstVolumeEntries: List<ManifestEntry> get() = entries.filter { it.volume == 0 && !it.continuation }

    companion object {
        fun from(name: String, f: Map<String, Any?>) = ManifestFixture(
            name = name,
            files = f["files"] as List<String>,
            listPassword = f["listPassword"] as String?,
            headerEncrypted = f["headerEncrypted"] as Boolean,
            solid = f["solid"] as Boolean,
            details = f["details"] as String,
            entries = (f["entries"] as List<Map<String, Any?>>).map(ManifestEntry::from),
            filterTypes = ((f["filters"] as Map<String, Any?>?)?.get("types") as List<Long>?)?.map { it.toInt() },
            filterCount = ((f["filters"] as Map<String, Any?>?)?.get("count") as Long?)?.toInt(),
        )
    }
}

@Suppress("UNCHECKED_CAST")
internal data class ManifestEntry(
    val volume: Int,
    val ordinal: Int,
    val continuation: Boolean,
    val name: String,
    val type: String,
    val size: Long?,
    val sha256: String?,
    val crc32: String?,
    val mtimeNs: Long?,
    val linkTarget: String?,
    val version: Int?,
    val packedSize: Long?,
    val password: String?,
    val encrypted: Boolean,
    val solid: Boolean,
    val splitAcrossVolumes: Boolean,
    val ltChecksum: List<String>?,
    val ltCompression: String?,
) {
    val stored: Boolean get() = ltCompression?.contains(" -m0") == true

    companion object {
        fun from(e: Map<String, Any?>) = ManifestEntry(
            volume = (e["volume"] as Long).toInt(),
            ordinal = (e["ordinal"] as Long).toInt(),
            continuation = e["continuation"] as Boolean,
            name = e["name"] as String,
            type = e["type"] as String,
            size = e["size"] as Long?,
            sha256 = e["sha256"] as String?,
            crc32 = e["crc32"] as String?,
            mtimeNs = e["mtimeNs"] as Long?,
            linkTarget = e["linkTarget"] as String?,
            version = (e["version"] as Long?)?.toInt(),
            packedSize = e["packedSize"] as Long?,
            password = e["password"] as String?,
            encrypted = e["encrypted"] as Boolean,
            solid = e["solid"] as Boolean,
            splitAcrossVolumes = e["splitAcrossVolumes"] as Boolean,
            ltChecksum = e["ltChecksum"] as List<String>?,
            ltCompression = e["ltCompression"] as String?,
        )
    }
}

@Suppress("UNCHECKED_CAST")
internal data class LibarchiveFixture(
    val name: String,
    val ltExitCode: Int,
    val details: String?,
    val entries: List<LibarchiveEntry>,
) {
    companion object {
        fun from(name: String, f: Map<String, Any?>) = LibarchiveFixture(
            name = name,
            ltExitCode = (f["ltExitCode"] as Long).toInt(),
            details = f["details"] as String?,
            entries = (f["entries"] as List<Map<String, Any?>>).map { e ->
                LibarchiveEntry(
                    name = e["name"] as String,
                    ltType = e["ltType"] as String?,
                    size = e["size"] as Long?,
                    packedSize = e["packedSize"] as Long?,
                    ratio = e["ratio"] as String?,
                    flags = e["flags"] as String?,
                    target = e["target"] as String?,
                    version = (e["version"] as Long?)?.toInt(),
                    compression = e["compression"] as String?,
                    checksum = e["checksum"] as List<String>?,
                    sha256 = e["sha256"] as String?,
                    password = e["password"] as String?,
                )
            },
        )
    }
}

internal data class LibarchiveEntry(
    val name: String,
    val ltType: String?,
    val size: Long?,
    val packedSize: Long?,
    val ratio: String?,
    val flags: String?,
    val target: String?,
    val version: Int?,
    val compression: String?,
    val checksum: List<String>?,
    val sha256: String?,
    /** The password libarchive's tests use for this entry, null for an unencrypted one. */
    val password: String?,
) {
    /** rar lt shows versioned names as "name;N". */
    val baseName: String get() = if (version != null) name.substringBeforeLast(';') else name
}

/** Returns at most one byte per read. */
internal class OneByteInputStream(stream: InputStream) : FilterInputStream(stream) {
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
}

/** skip() never makes progress. */
internal class NoSkipInputStream(stream: InputStream) : FilterInputStream(stream) {
    override fun skip(n: Long): Long = 0
}

/** Records whether close() was called. */
internal class TrackingInputStream(stream: InputStream) : FilterInputStream(stream) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}
