package eu.darken.butler.common.hashing

import eu.darken.butler.common.hashing.Hasher.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okio.source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.json.writeToFile
import java.io.File
import kotlin.uuid.Uuid

class HasherTest : BaseTest() {

    @Test fun `MD5 direct`() = runTest {
        "SD Maid 2/SE"
            .hash(Type.MD5)
            .format() shouldBe "7604efc2f2f3dd78558b0f54d5fda072"
        "SD Maid 2/SE"
            .toByteArray()
            .hash(Type.MD5)
            .format() shouldBe "7604efc2f2f3dd78558b0f54d5fda072"
    }

    @Test fun `SHA1 direct`() = runTest {
        "SD Maid 2/SE"
            .hash(Type.SHA1)
            .format() shouldBe "e0b61786d683c92f873f19bf740f569df0a547e5"
        "SD Maid 2/SE"
            .toByteArray()
            .hash(Type.SHA1)
            .format() shouldBe "e0b61786d683c92f873f19bf740f569df0a547e5"
    }

    @Test fun `SHA256 direct`() = runTest {
        "SD Maid 2/SE"
            .hash(Type.SHA256)
            .format() shouldBe "b3f545cdd32949087fa68f7c2adb3782e21204067912b1b3d893270c94d67d12"
        "SD Maid 2/SE"
            .toByteArray()
            .hash(Type.SHA256)
            .format() shouldBe "b3f545cdd32949087fa68f7c2adb3782e21204067912b1b3d893270c94d67d12"
    }

    @Test fun `MD5 from file`(@TempDir tempDir: File) = runTest {
        File(tempDir, "MD5-${Uuid.random()}")
            .apply { "SD Maid 2/SE".writeToFile(this) }
            .source()
            .hash(Type.MD5)
            .format() shouldBe "7604efc2f2f3dd78558b0f54d5fda072"
    }

    @Test fun `SHA1 from file`(@TempDir tempDir: File) = runTest {
        File(tempDir, "SHA1-${Uuid.random()}")
            .apply { "SD Maid 2/SE".writeToFile(this) }
            .source()
            .hash(Type.SHA1)
            .format() shouldBe "e0b61786d683c92f873f19bf740f569df0a547e5"
    }

    @Test fun `SHA256 from file`(@TempDir tempDir: File) = runTest {
        File(tempDir, "SHA256-${Uuid.random()}")
            .apply { "SD Maid 2/SE".writeToFile(this) }
            .source()
            .hash(Type.SHA256)
            .format() shouldBe "b3f545cdd32949087fa68f7c2adb3782e21204067912b1b3d893270c94d67d12"
    }
}