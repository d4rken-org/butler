package eu.darken.butler.common.pkgs.attribution

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.LocalPathNormalization
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class AppFolderRecognizerTest : BaseTest() {

    private var originalPrimary = ""

    @BeforeEach
    fun setup() {
        originalPrimary = LocalPathNormalization.primaryStorage
        LocalPathNormalization.primaryStorage = "/storage/emulated/0"
    }

    @AfterEach
    fun teardown() {
        LocalPathNormalization.primaryStorage = originalPrimary
    }

    private fun recognize(path: String) = AppFolderRecognizer.recognize(LocalPath.build(File(path)))

    @Test
    fun `private data areas carry their user`() {
        recognize("/data/data/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/data/user/0/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/data/user/10/com.example") shouldBe AppFolder("com.example", 10)
        recognize("/data/user_de/10/com.example") shouldBe AppFolder("com.example", 10)
    }

    @Test
    fun `adopted storage private areas carry their user`() {
        recognize("/mnt/expand/1a2b-3c4d/user/0/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/mnt/expand/1a2b-3c4d/user_de/11/com.example") shouldBe AppFolder("com.example", 11)
    }

    @Test
    fun `public areas on emulated storage carry their user`() {
        recognize("/storage/emulated/0/Android/data/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/storage/emulated/10/Android/obb/com.example") shouldBe AppFolder("com.example", 10)
        recognize("/storage/emulated/0/Android/media/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/data/media/10/Android/data/com.example") shouldBe AppFolder("com.example", 10)
    }

    @Test
    fun `primary storage aliases resolve to the current user's storage`() {
        recognize("/sdcard/Android/data/com.example") shouldBe AppFolder("com.example", 0)
        recognize("/storage/self/primary/Android/media/com.example") shouldBe AppFolder("com.example", 0)

        LocalPathNormalization.primaryStorage = "/storage/emulated/10"
        recognize("/sdcard/Android/data/com.example") shouldBe AppFolder("com.example", 10)
    }

    @Test
    fun `public areas on other volumes have no owning user`() {
        recognize("/storage/1A2B-3C4D/Android/data/com.example") shouldBe AppFolder("com.example", null)
        recognize("/mnt/media_rw/1A2B-3C4D/Android/obb/com.example") shouldBe AppFolder("com.example", null)
    }

    @Test
    fun `shared obb locations are not attributed`() {
        recognize("/storage/emulated/obb/com.example") shouldBe null
        recognize("/data/media/obb/com.example") shouldBe null
    }

    @Test
    fun `only immediate children of an app area count`() {
        recognize("/storage/emulated/0/Android/data/com.example/files") shouldBe null
        recognize("/storage/emulated/0/Android/data") shouldBe null
        recognize("/data/user/0") shouldBe null
        recognize("/storage/emulated/0/Download/com.example") shouldBe null
        recognize("/storage/emulated/0/Android/other/com.example") shouldBe null
    }

    @Test
    fun `names that are no package names are ignored`() {
        recognize("/storage/emulated/0/Android/data/.nomedia") shouldBe null
        recognize("/storage/emulated/0/Android/media/WhatsApp") shouldBe null
        recognize("/data/data/1com.example") shouldBe null
        recognize("/data/data/com..example") shouldBe null
    }

    @Test
    fun `user segments must be numeric`() {
        recognize("/data/user/abc/com.example") shouldBe null
        recognize("/storage/emulated/legacy/Android/data/com.example") shouldBe null
    }
}
