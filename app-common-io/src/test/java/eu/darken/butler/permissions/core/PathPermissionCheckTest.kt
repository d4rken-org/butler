package eu.darken.butler.permissions.core

import android.content.Intent
import android.net.Uri
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.saf.AndroidDataAccessChecker
import eu.darken.butler.common.storage.saf.SAFPickerIntentBuilder
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.SetupStateProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PathPermissionCheckTest : BaseTest() {

    private fun createChecker(
        apiLevel: Int,
        androidDataPath: LocalPath? = null,
        androidObbPath: LocalPath? = null,
        hasExistingSAFPermission: Boolean = false,  // Does SAFLocationManager have permission?
        documentUIAllowed: Boolean = true,
        safPickerIntent: Intent? = null,
        completedTypes: Set<SetupModule.Type> = emptySet(),
        rootAvailable: Boolean = true,
        rootInstalled: Boolean = false,
        shizukuAvailable: Boolean = true,
        shizukuInstalled: Boolean = false,
        setupStates: Flow<SetupStateProvider.State>? = null,
    ): PathPermissionCheck {
        // Create setup modules for all possible types
        val modules = mapOf(
            SetupModule.Type.STORAGE to mockk<SetupModule.State.Current>(relaxed = true) {
                every { type } returns SetupModule.Type.STORAGE
                every { isComplete } returns (SetupModule.Type.STORAGE in completedTypes)
                every { isAvailable } returns false
            },
            SetupModule.Type.ROOT to mockk<SetupModule.State.Current>(relaxed = true) {
                every { type } returns SetupModule.Type.ROOT
                every { isComplete } returns (SetupModule.Type.ROOT in completedTypes)
                every { isAvailable } returns rootAvailable
                every { isInstalled } returns rootInstalled
            },
            SetupModule.Type.SHIZUKU to mockk<SetupModule.State.Current>(relaxed = true) {
                every { type } returns SetupModule.Type.SHIZUKU
                every { isComplete } returns (SetupModule.Type.SHIZUKU in completedTypes)
                every { isAvailable } returns shizukuAvailable
                every { isInstalled } returns shizukuInstalled
            }
        )

        val providerState = SetupStateProvider.State(modules = modules)

        val setupStateProvider = mockk<SetupStateProvider>(relaxed = true) {
            every { state } returns (setupStates ?: flowOf(providerState))
        }

        val accessChecker = mockk<LocalPathAccessChecker>(relaxed = true) {
            every { shouldTryNormalAccess(any(), any()) } returns true
        }

        val storageEnvironment = mockk<StorageEnvironment>(relaxed = true) {
            every { publicDataDirs } returns listOfNotNull(androidDataPath)
            every { publicObbDirs } returns listOfNotNull(androidObbPath)
            every { publicStorages } returns emptyList()
            every { ourPrivateDirs } returns emptyList()
            every { ourPublicDirs } returns emptyList()
            every { systemDir } returns LocalPath.build("/system")
            every { dataDir } returns LocalPath.build("/data")
        }

        // SAFLocationManager.toSAFPath: Returns SAFPath only if permission exists
        val mockSAFPath = if (hasExistingSAFPermission) {
            mockk<SAFPath>(relaxed = true) {
                every { pathUri } returns SafUri.fromAndroidUri(Uri.parse("content://test"))
                every { segments } returns emptyList()
            }
        } else null

        val safLocationManager = mockk<SAFLocationManager>(relaxed = true) {
            every { toSAFPath(any<LocalPath>()) } returns mockSAFPath
        }

        val androidDataAccessChecker = mockk<AndroidDataAccessChecker>(relaxed = true) {
            coEvery { canUseSAFForAndroidData() } returns documentUIAllowed
        }

        val safPickerIntentBuilder = mockk<SAFPickerIntentBuilder>(relaxed = true) {
            coEvery { buildPickerIntent(any()) } returns safPickerIntent
        }

        val apiLevelProvider = mockk<eu.darken.butler.common.ApiLevel>(relaxed = true) {
            every { has(any()) } answers {
                val requestedLevel = firstArg<Int>()
                apiLevel >= requestedLevel
            }
        }

        return PathPermissionCheck(
            setupStateProvider = setupStateProvider,
            accessChecker = accessChecker,
            storageEnvironment = storageEnvironment,
            safLocationManager = safLocationManager,
            androidDataAccessChecker = androidDataAccessChecker,
            safPickerIntentBuilder = safPickerIntentBuilder,
            apiLevel = apiLevelProvider
        )
    }

    @Test
    fun `test Android below 30 - Android data path requires storage only`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 29,
            androidDataPath = androidDataPath
        )

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldBeNull()
        requirements.alternativePath.shouldBeNull()
        requirements.combos shouldBe setOf(setOf(SetupModule.Type.STORAGE))
    }

    @Test
    fun `test Android 30-32 with no SAF permission and DocumentsUI allowed - returns SAF picker`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val mockIntent = Intent()

        val checker = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            hasExistingSAFPermission = false,
            documentUIAllowed = true,
            safPickerIntent = mockIntent
        )

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldNotBeNull()
        requirements.safPickerGrant.intent shouldBe mockIntent
        requirements.safPickerGrant.targetPath shouldBe testPath
        requirements.alternativePath.shouldBeNull()
        // Also provides ROOT/SHIZUKU as fallback options
        requirements.combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
    }

    @Test
    fun `test Android 30-32 with existing SAF permission - returns alternative path`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            hasExistingSAFPermission = true
        )

        val requirements = checker.monitor(testPath).first()

        requirements.alternativePath.shouldNotBeNull()
        requirements.alternativePath.shouldBeInstanceOf<SAFPath>()
        requirements.safPickerGrant.shouldBeNull()
        requirements.combos shouldBe emptySet()
    }

    @Test
    fun `test Android 30-32 can construct SAFPath but no permission - returns SAF picker`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val mockIntent = Intent()

        val checker = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            hasExistingSAFPermission = false,  // Permission doesn't exist yet
            documentUIAllowed = true,
            safPickerIntent = mockIntent
        )

        val requirements = checker.monitor(testPath).first()

        // Should offer SAF picker, NOT return alternativePath
        requirements.safPickerGrant.shouldNotBeNull()
        requirements.safPickerGrant.intent shouldBe mockIntent
        requirements.alternativePath.shouldBeNull()
        // Also provides ROOT/SHIZUKU as fallback options
        requirements.combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
    }

    @Test
    fun `test Android 30-32 with DocumentsUI restricted - returns Root Shizuku combos`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            hasExistingSAFPermission = false,
            documentUIAllowed = false
        )

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldBeNull()
        requirements.alternativePath.shouldBeNull()
        requirements.combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
    }

    @Test
    fun `test Android 33+ - returns Root Shizuku combos no SAF`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath
        )

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldBeNull()
        requirements.alternativePath.shouldBeNull()
        requirements.combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
    }

    @Test
    fun `test Android 33+ - offers root when no root manager is installed`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            rootAvailable = false,
            rootInstalled = false,
            shizukuAvailable = false,
        )

        val requirements = checker.monitor(testPath).first()

        requirements.combos shouldBe setOf(setOf(SetupModule.Type.ROOT))
        requirements.needsSetup shouldBe true
    }

    @Test
    fun `test Android 33+ - offers root when the manager is unrecognised but root works`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            rootAvailable = true,
            rootInstalled = false,
            shizukuAvailable = false,
        )

        val requirements = checker.monitor(testPath).first()

        requirements.combos shouldBe setOf(setOf(SetupModule.Type.ROOT))
        requirements.rootInstalled shouldBe false
    }

    @Test
    fun `test Android 33+ - Shizuku stays gated on install`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val withoutShizuku = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            shizukuAvailable = false,
        ).monitor(testPath).first()
        withoutShizuku.combos.flatten() shouldNotContain SetupModule.Type.SHIZUKU

        val withShizuku = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            shizukuAvailable = true,
        ).monitor(testPath).first()
        withShizuku.combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
    }

    @Test
    fun `test Android 30-32 with DocumentsUI restricted - offers root when no root manager is installed`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val checker = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            documentUIAllowed = false,
            rootAvailable = false,
        )

        val requirements = checker.monitor(testPath).first()

        requirements.combos shouldContain setOf(SetupModule.Type.ROOT)
    }

    @Test
    fun `test Android obb path same logic as Android data`() = runTest {
        val androidObbPath = LocalPath.build("/storage/emulated/0/Android/obb")
        val testPath = LocalPath.build("/storage/emulated/0/Android/obb/com.example")
        val mockIntent = Intent()

        val checker = createChecker(
            apiLevel = 30,
            androidObbPath = androidObbPath,
            hasExistingSAFPermission = false,
            documentUIAllowed = true,
            safPickerIntent = mockIntent
        )

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldNotBeNull()
        requirements.safPickerGrant.intent shouldBe mockIntent
    }

    @Test
    fun `test normal path returns empty requirements`() = runTest {
        val testPath = LocalPath.build("/storage/emulated/0/Documents")

        val checker = createChecker(apiLevel = 30)

        val requirements = checker.monitor(testPath).first()

        requirements.safPickerGrant.shouldBeNull()
        requirements.alternativePath.shouldBeNull()
        requirements.combos shouldBe emptySet()
    }

    @Test
    fun `test calls androidDataAccessChecker when needed`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val mockIntent = Intent()

        val androidDataAccessChecker = mockk<AndroidDataAccessChecker>(relaxed = true) {
            coEvery { canUseSAFForAndroidData() } returns true
        }

        val providerState = SetupStateProvider.State(modules = emptyMap())

        val setupStateProvider = mockk<SetupStateProvider>(relaxed = true) {
            every { state } returns flowOf(providerState)
        }

        val storageEnvironment = mockk<StorageEnvironment>(relaxed = true) {
            every { publicDataDirs } returns listOf(androidDataPath)
            every { publicObbDirs } returns emptyList()
            every { publicStorages } returns emptyList()
            every { ourPrivateDirs } returns emptyList()
            every { ourPublicDirs } returns emptyList()
            every { systemDir } returns LocalPath.build("/system")
            every { dataDir } returns LocalPath.build("/data")
        }

        val safLocationManager = mockk<SAFLocationManager>(relaxed = true) {
            every { toSAFPath(any<LocalPath>()) } returns null
        }

        val safPickerIntentBuilder = mockk<SAFPickerIntentBuilder>(relaxed = true) {
            coEvery { buildPickerIntent(any()) } returns mockIntent
        }

        val apiLevel = mockk<eu.darken.butler.common.ApiLevel>(relaxed = true) {
            every { has(any()) } answers {
                val requestedLevel = firstArg<Int>()
                30 >= requestedLevel
            }
        }

        val checker = PathPermissionCheck(
            setupStateProvider = setupStateProvider,
            accessChecker = mockk(relaxed = true) {
                every { shouldTryNormalAccess(any(), any()) } returns true
            },
            storageEnvironment = storageEnvironment,
            safLocationManager = safLocationManager,
            androidDataAccessChecker = androidDataAccessChecker,
            safPickerIntentBuilder = safPickerIntentBuilder,
            apiLevel = apiLevel
        )

        checker.monitor(testPath).first()

        coVerify { androidDataAccessChecker.canUseSAFForAndroidData() }
    }

    @Test
    fun `batch monitor combines combos as a conjunction across roots`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
        )
        val restrictedPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val rootOnlyPath = LocalPath.build("/data/local/tmp")

        val requirements = checker.monitor(listOf(restrictedPath, rootOnlyPath)).first()

        // Android/data (13+) allows ROOT or SHIZUKU, but /data needs ROOT — Shizuku alone must
        // NOT be advertised as sufficient. Only ROOT unlocks every path ({ROOT,SHIZUKU} is a
        // pruned superset).
        requirements.combos shouldBe setOf(setOf(SetupModule.Type.ROOT))
        requirements.needsSetup shouldBe true
    }

    @Test
    fun `batch monitor keeps tracking setup completion when a SAF picker exists`() = runTest {
        // Android 11-12: the single-path monitor short-circuits on a viable picker and stops
        // observing setup state. The batch monitor must not — completing root has to be reflected
        // so the suggestion clears and dependent reruns can trigger.
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val before = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            safPickerIntent = Intent(),
        ).monitor(listOf(testPath)).first()
        before.combos shouldBe setOf(setOf(SetupModule.Type.ROOT), setOf(SetupModule.Type.SHIZUKU))
        before.safPickerGrant.shouldBeNull()
        before.needsSetup shouldBe true

        val after = createChecker(
            apiLevel = 30,
            androidDataPath = androidDataPath,
            safPickerIntent = Intent(),
            completedTypes = setOf(SetupModule.Type.ROOT),
        ).monitor(listOf(testPath)).first()
        after.complete shouldBe setOf(SetupModule.Type.ROOT)
        after.needsSetup shouldBe false
    }

    @Test
    fun `batch monitor skips paths accessible through an existing SAF grant`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            hasExistingSAFPermission = true,
        )

        val requirements = checker.monitor(listOf(testPath)).first()

        // Accessible right now via SAF — nothing to suggest
        requirements shouldBe PathRequirements()
    }

    @Test
    fun `batch monitor collapses descendants to their ancestor`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
        )
        val parent = LocalPath.build("/storage/emulated/0/Android/data/com.example")
        val child = LocalPath.build("/storage/emulated/0/Android/data/com.example/files/deep")

        val batch = checker.monitor(listOf(parent, child)).first()
        val single = checker.monitor(parent).first()

        batch.combos shouldBe single.combos
    }

    @Test
    fun `batch monitor with no paths yields empty requirements`() = runTest {
        val checker = createChecker(apiLevel = 33)

        val requirements = checker.monitor(emptyList()).first()

        requirements shouldBe PathRequirements()
        requirements.needsSetup shouldBe false
    }

    @Test
    fun `batch monitor picks up a mechanism that resolves after the first emission`() = runTest {
        val androidDataPath = LocalPath.build("/storage/emulated/0/Android/data")
        val testPath = LocalPath.build("/storage/emulated/0/Android/data/com.example")

        val rootModule = mockk<SetupModule.State.Current>(relaxed = true) {
            every { type } returns SetupModule.Type.ROOT
            every { isComplete } returns false
            every { isAvailable } returns true
            every { isInstalled } returns false
        }
        val shizukuLoading = mockk<SetupModule.State.Loading>(relaxed = true) {
            every { type } returns SetupModule.Type.SHIZUKU
        }
        val shizukuResolved = mockk<SetupModule.State.Current>(relaxed = true) {
            every { type } returns SetupModule.Type.SHIZUKU
            every { isComplete } returns false
            every { isAvailable } returns true
            every { isInstalled } returns true
        }

        val setupStates = MutableStateFlow(
            SetupStateProvider.State(
                modules = mapOf(
                    SetupModule.Type.ROOT to rootModule,
                    SetupModule.Type.SHIZUKU to shizukuLoading,
                )
            )
        )

        val checker = createChecker(
            apiLevel = 33,
            androidDataPath = androidDataPath,
            setupStates = setupStates,
        )

        val emissions = mutableListOf<PathRequirements>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            checker.monitor(listOf(testPath)).collect { emissions.add(it) }
        }

        emissions.last().combos shouldBe setOf(setOf(SetupModule.Type.ROOT))

        setupStates.value = SetupStateProvider.State(
            modules = mapOf(
                SetupModule.Type.ROOT to rootModule,
                SetupModule.Type.SHIZUKU to shizukuResolved,
            )
        )

        emissions.last().combos shouldBe setOf(
            setOf(SetupModule.Type.ROOT),
            setOf(SetupModule.Type.SHIZUKU)
        )
        job.cancel()
    }
}
