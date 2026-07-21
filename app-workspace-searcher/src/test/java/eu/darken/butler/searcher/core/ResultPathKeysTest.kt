package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ResultPathKeysTest : BaseTest() {

    @Test
    fun `primary storage aliases normalize to the canonical spelling`() {
        listOf(
            "/sdcard/DCIM/a.jpg",
            "/storage/self/primary/DCIM/a.jpg",
            "/mnt/sdcard/DCIM/a.jpg",
            "/mnt/user/0/primary/DCIM/a.jpg",
        ).forEach { alias ->
            ResultPathKeys.keyOf(LocalPath.build(alias)) shouldBe "/storage/emulated/0/DCIM/a.jpg"
        }
    }

    @Test
    fun `alias root itself normalizes`() {
        ResultPathKeys.keyOf(LocalPath.build("/sdcard")) shouldBe "/storage/emulated/0"
    }

    @Test
    fun `canonical spelling is unchanged`() {
        ResultPathKeys.keyOf(LocalPath.build("/storage/emulated/0/Music/x.mp3")) shouldBe
            "/storage/emulated/0/Music/x.mp3"
    }

    @Test
    fun `dot segments are resolved syntactically`() {
        ResultPathKeys.keyOf(LocalPath.build("/storage/emulated/0/Download/./sub/../a.txt")) shouldBe
            "/storage/emulated/0/Download/a.txt"
    }

    @Test
    fun `non-primary volumes are untouched`() {
        ResultPathKeys.keyOf(LocalPath.build("/storage/4BBD-D3E7/a.txt")) shouldBe "/storage/4BBD-D3E7/a.txt"
    }

    @Test
    fun `sdcardish prefixes that are not aliases stay untouched`() {
        ResultPathKeys.keyOf(LocalPath.build("/sdcard2/a.txt")) shouldBe "/sdcard2/a.txt"
    }

    @Test
    fun `comparable returns same instance when already canonical`() {
        val path = LocalPath.build("/storage/emulated/0/a.txt")
        ResultPathKeys.comparable(path) shouldBeSameInstanceAs path
    }

    @Test
    fun `comparable rewrites alias paths`() {
        ResultPathKeys.comparable(LocalPath.build("/sdcard/a.txt")) shouldBe
            LocalPath.build("/storage/emulated/0/a.txt")
    }

    @Test
    fun `aliases resolve to the current android user's storage root`() {
        val original = ResultPathKeys.primaryStorage
        try {
            ResultPathKeys.primaryStorage = "/storage/emulated/10"

            ResultPathKeys.keyOf(LocalPath.build("/sdcard/DCIM/a.jpg")) shouldBe
                "/storage/emulated/10/DCIM/a.jpg"
            ResultPathKeys.keyOf(LocalPath.build("/mnt/user/10/primary/DCIM/a.jpg")) shouldBe
                "/storage/emulated/10/DCIM/a.jpg"
            // User 0's /mnt/user alias must NOT match under user 10
            ResultPathKeys.keyOf(LocalPath.build("/mnt/user/0/primary/DCIM/a.jpg")) shouldBe
                "/mnt/user/0/primary/DCIM/a.jpg"
        } finally {
            ResultPathKeys.primaryStorage = original
        }
    }
}
