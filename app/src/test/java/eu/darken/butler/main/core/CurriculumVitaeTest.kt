package eu.darken.butler.main.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.serialization.SerializationAppModule
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CurriculumVitaeTest : BaseTest() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun teardown() {
        appScope.cancel()
    }

    private fun brokenPackageInfoContext(): Context {
        val base: Context = ApplicationProvider.getApplicationContext()
        val brokenPm = mockk<PackageManager> {
            every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException("nope")
        }
        return object : ContextWrapper(base) {
            override fun getPackageManager(): PackageManager = brokenPm
        }
    }

    @Test
    fun `updateAppLaunch does not propagate stats failures`() {
        val vitae = CurriculumVitae(
            context = brokenPackageInfoContext(),
            appScope = appScope,
            json = SerializationAppModule().json(),
        )

        val job = vitae.updateAppLaunch()
        runBlocking { job.join() }

        job.isCompleted shouldBe true
        job.isCancelled shouldBe false
    }
}
