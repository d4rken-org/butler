package eu.darken.butler.common.storage.saf

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SAFPickerIntentBuilderTest : BaseTest() {

    @Test
    fun `test builds picker intent for valid path`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0/Android/data")

        val mockTreeRootUri = mockk<SafUri> {
            every { toAndroidUri() } returns Uri.parse("content://com.android.externalstorage.documents/tree/primary")
        }

        val safPath = mockk<SAFPath> {
            every { treeRootUri } returns mockTreeRootUri
            every { segments } returns listOf("Android", "data")
        }

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns safPath
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldNotBeNull()
        intent.action shouldBe Intent.ACTION_OPEN_DOCUMENT_TREE
        intent.hasExtra("android.content.extra.SHOW_ADVANCED") shouldBe true
        intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI) shouldBe true
    }

    @Test
    fun `test returns null for unmappable path`() = runTest {
        val targetPath = LocalPath.build("/invalid/path")

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns null
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldBeNull()
    }

    @Test
    fun `test handles exception during path mapping`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0/Android/data")

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } throws RuntimeException("Test exception")
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldBeNull()
    }

    @Test
    fun `test builds correct URI structure for Android data directory`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0/Android/data")

        // Mock SAFPath with treeRootUri and segments
        val mockTreeRootUri = mockk<SafUri> {
            every { toAndroidUri() } returns Uri.parse("content://com.android.externalstorage.documents/tree/primary")
        }

        val safPath = mockk<SAFPath> {
            every { treeRootUri } returns mockTreeRootUri
            every { segments } returns listOf("Android", "data")
        }

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns safPath
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldNotBeNull()

        val navUri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        navUri.shouldNotBeNull()

        // Verify the URI has the correct structure:
        // tree/primary:Android/data/document/primary:Android/data
        navUri.toString() shouldBe "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/primary%3AAndroid%2Fdata"
    }

    @Test
    fun `test builds correct URI structure for Android obb directory`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0/Android/obb")

        val mockTreeRootUri = mockk<SafUri> {
            every { toAndroidUri() } returns Uri.parse("content://com.android.externalstorage.documents/tree/primary")
        }

        val safPath = mockk<SAFPath> {
            every { treeRootUri } returns mockTreeRootUri
            every { segments } returns listOf("Android", "obb")
        }

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns safPath
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldNotBeNull()

        val navUri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        navUri.shouldNotBeNull()

        navUri.toString() shouldBe "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fobb/document/primary%3AAndroid%2Fobb"
    }

    @Test
    fun `test builds correct URI for root storage path with no segments`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0")

        val mockTreeRootUri = mockk<SafUri> {
            every { toAndroidUri() } returns Uri.parse("content://com.android.externalstorage.documents/tree/primary")
        }

        val safPath = mockk<SAFPath> {
            every { treeRootUri } returns mockTreeRootUri
            every { segments } returns emptyList()
        }

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns safPath
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldNotBeNull()

        val navUri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        navUri.shouldNotBeNull()

        // When no segments, should just use the root document ID
        navUri.toString() shouldBe "content://com.android.externalstorage.documents/tree/primary/document/primary"
    }

    @Test
    fun `test builds correct URI for deeply nested path`() = runTest {
        val targetPath = LocalPath.build("/storage/emulated/0/Android/data/com.example.app/files")

        val mockTreeRootUri = mockk<SafUri> {
            every { toAndroidUri() } returns Uri.parse("content://com.android.externalstorage.documents/tree/primary")
        }

        val safPath = mockk<SAFPath> {
            every { treeRootUri } returns mockTreeRootUri
            every { segments } returns listOf("Android", "data", "com.example.app", "files")
        }

        val safLocationManager = mockk<SAFLocationManager> {
            every { toSAFPath(targetPath) } returns safPath
        }

        val builder = SAFPickerIntentBuilder(safLocationManager)
        val intent = builder.buildPickerIntent(targetPath)

        intent.shouldNotBeNull()

        val navUri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        navUri.shouldNotBeNull()

        navUri.toString() shouldBe "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.example.app%2Ffiles/document/primary%3AAndroid%2Fdata%2Fcom.example.app%2Ffiles"
    }
}
