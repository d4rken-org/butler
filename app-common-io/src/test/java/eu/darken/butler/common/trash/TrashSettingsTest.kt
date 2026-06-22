package eu.darken.butler.common.trash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.datastore.value
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TrashSettingsTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun create() = TrashSettings(context = context, json = Json)

    @Test
    fun `recycle bin is enabled by default`() = runTest {
        // A fresh install must default to moving deletions into the recycle bin,
        // so the normal delete action is recoverable instead of a permanent wipe.
        create().enabled.value() shouldBe true
    }
}
