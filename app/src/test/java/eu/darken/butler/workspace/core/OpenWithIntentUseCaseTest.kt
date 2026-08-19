package eu.darken.butler.workspace.core

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

/**
 * Lives in :app because FileProvider resolves against the application's provider authority, which
 * only the app manifest declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenWithIntentUseCaseTest : BaseTest() {

    private lateinit var application: Application
    private lateinit var useCase: OpenWithIntentUseCase
    private lateinit var file: File

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        useCase = OpenWithIntentUseCase(application)
        file = File.createTempFile("viewer-open-with", ".jpg").apply { deleteOnExit() }
        file.writeBytes(ByteArray(8))
    }

    private fun path() = LocalPath.build(file.absolutePath)

    private fun registerHandler(intent: Intent) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.gallery"
                name = "com.example.gallery.ViewActivity"
            }
        }
        shadowOf(application.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    @Test
    fun `the view intent asks for read permission and stays chooser-free`() {
        val intent = useCase.createViewIntent(path(), "image/jpeg").shouldNotBeNull()

        intent.action shouldBe Intent.ACTION_VIEW
        intent.type shouldBe "image/jpeg"
        intent.data?.scheme shouldBe "content"
        (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
        // The flag belongs on whatever is actually started, which is the chooser.
        (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) shouldBe 0
    }

    @Test
    fun `SAF paths get no intent`() {
        val safPath = SAFPath.build("content://com.example.provider/tree/primary", "photo.jpg")

        useCase.createViewIntent(safPath, "image/jpeg").shouldBeNull()
    }

    @Test
    fun `a missing file gets no intent`() {
        val missing = LocalPath.build("${file.parent}/definitely-not-here.jpg")

        useCase.createViewIntent(missing, "image/jpeg").shouldBeNull()
    }

    @Test
    fun `a file that only elevated access can read gets no intent`() {
        file.setReadable(false, false)
        // Running as root would defeat the permission bit, and then there is nothing to assert.
        assumeTrue(!file.canRead())

        useCase.createViewIntent(path(), "image/jpeg").shouldBeNull()
    }

    /**
     * Butler's own manifest declares an `ACTION_VIEW` filter for every file, so it always resolves
     * this intent. That deliberately does not count as a handler: "Open with" offers other apps, and
     * a chooser containing only the app the user is already in is no answer. Nothing else is
     * registered here, so the guard must report no handler.
     */
    @Test
    fun `no handler means no launch`() {
        useCase.openWithChooser(path(), "image/jpeg", "Open with") shouldBe false

        shadowOf(application).nextStartedActivity.shouldBeNull()
    }

    @Test
    fun `a handled file is launched through a chooser carrying the new task flag`() {
        registerHandler(useCase.createViewIntent(path(), "image/jpeg")!!)

        useCase.openWithChooser(path(), "image/jpeg", "Open with") shouldBe true

        val started = shadowOf(application).nextStartedActivity.shouldNotBeNull()
        started.action shouldBe Intent.ACTION_CHOOSER
        (started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) shouldBe Intent.FLAG_ACTIVITY_NEW_TASK

        val inner = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java).shouldNotBeNull()
        inner.action shouldBe Intent.ACTION_VIEW
        inner.type shouldBe "image/jpeg"
    }
}
