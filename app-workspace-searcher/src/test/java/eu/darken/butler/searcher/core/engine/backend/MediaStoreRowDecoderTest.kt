package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class MediaStoreRowDecoderTest : BaseTest() {

    @Test
    fun `valid row decodes into a file lookup`() {
        val outcome = MediaStoreRowDecoder.decode(
            MediaStoreRow(
                data = "/storage/emulated/0/DCIM/photo.jpg",
                size = 1234L,
                modifiedAtEpochSeconds = 1_700_000_000L,
            )
        )

        val lookup = outcome.shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>().lookup
        lookup.lookedUp shouldBe LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe 1234L
        lookup.modifiedAt shouldBe Instant.fromEpochSeconds(1_700_000_000L)
        lookup.createdAt shouldBe null
    }

    @Test
    fun `null DATA is unrepresentable, not an error`() {
        MediaStoreRowDecoder.decode(
            MediaStoreRow(data = null, size = 1L, modifiedAtEpochSeconds = 1L)
        ) shouldBe MediaStoreRowDecoder.Outcome.Unrepresentable
    }

    @Test
    fun `blank DATA is unrepresentable, not an error`() {
        MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "  ", size = 1L, modifiedAtEpochSeconds = 1L)
        ) shouldBe MediaStoreRowDecoder.Outcome.Unrepresentable
    }

    @Test
    fun `relative DATA is invalid`() {
        MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "DCIM/photo.jpg", size = 1L, modifiedAtEpochSeconds = 1L)
        ).shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Invalid>()
    }

    @Test
    fun `null size and date survive as null instead of zero`() {
        val outcome = MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "/storage/emulated/0/a.mp3", size = null, modifiedAtEpochSeconds = null)
        )

        val lookup = outcome.shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>().lookup
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
    }

    @Test
    fun `negative size becomes null`() {
        val outcome = MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "/storage/emulated/0/a.mp3", size = -1L, modifiedAtEpochSeconds = null)
        )

        outcome.shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>().lookup.size shouldBe null
    }

    @Test
    fun `zero epoch sentinel date becomes null`() {
        val outcome = MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "/storage/emulated/0/a.mp3", size = 1L, modifiedAtEpochSeconds = 0L)
        )

        outcome.shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>().lookup.modifiedAt shouldBe null
    }

    @Test
    fun `extreme future timestamp is preserved`() {
        val outcome = MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "/storage/emulated/0/a.mp3", size = 1L, modifiedAtEpochSeconds = 32_503_680_000L)
        )

        outcome.shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>()
            .lookup.modifiedAt shouldBe Instant.fromEpochSeconds(32_503_680_000L)
    }

    @Test
    fun `valid row after a malformed one still decodes`() {
        MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "not-absolute", size = 1L, modifiedAtEpochSeconds = 1L)
        ).shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Invalid>()

        MediaStoreRowDecoder.decode(
            MediaStoreRow(data = "/storage/emulated/0/ok.jpg", size = 1L, modifiedAtEpochSeconds = 1L)
        ).shouldBeInstanceOf<MediaStoreRowDecoder.Outcome.Decoded>()
    }
}
