package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test
import testhelpers.BaseTest

class ArchiveCompressionDefaultsTest : BaseTest() {

    private val json = Json

    @Test
    fun `serialization round trip for all combinations`() {
        ArchiveFormat.entries.forEach { format ->
            CompressionPreset.entries.forEach { level ->
                val original = ArchiveCompressionDefaults(format = format, level = level)
                val restored = json.decodeFromString<ArchiveCompressionDefaults>(json.encodeToString(original))
                restored shouldBe original
            }
        }
    }

    @Test
    fun `serial names stay stable`() {
        val encoded = json.encodeToString(ArchiveCompressionDefaults(ArchiveFormat.TAR_GZ, CompressionPreset.BEST))
        encoded shouldBe """{"format":"tar_gz","level":"best"}"""
    }

    @Test
    fun `defaults decode from empty object`() {
        val restored = Json { ignoreUnknownKeys = true }.decodeFromString<ArchiveCompressionDefaults>("{}")
        restored shouldBe ArchiveCompressionDefaults()
    }
}
