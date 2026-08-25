package eu.darken.butler.common.pkgs.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveDiskCache
import eu.darken.butler.common.files.archive.ArchiveEntryMeta
import eu.darken.butler.common.files.archive.ArchivePasswordStore
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppInstallInspectorTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    private val testPkg = "com.example.app"
    private val apkArchiveParser = mockk<ApkArchiveParser>()
    private val parsedPaths = mutableListOf<APath<*>>()

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "inspector_test").apply {
            deleteRecursively()
            mkdirs()
        }
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers { firstArg<LocalPath>().file.inputStream() }
            coEvery { file(any(), any()) } answers {
                okio.FileSystem.SYSTEM.openReadOnly(firstArg<LocalPath>().file.toOkioPath())
            }
            coEvery { lookup(any(), any<LookupOptions>()) } answers {
                val path = firstArg<LocalPath>()
                @Suppress("UNCHECKED_CAST")
                LocalPathLookup(
                    lookedUp = path,
                    fileType = if (path.file.isDirectory) FileType.DIRECTORY else FileType.FILE,
                    size = path.file.length(),
                    modifiedAt = Instant.fromEpochMilliseconds(path.file.lastModified()),
                ) as APathLookup<APath<*>>
            }
        }
        parsedPaths.clear()
        coEvery { apkArchiveParser.parseFile(any(), any()) } answers {
            parsedPaths += firstArg<APath<*>>()
            ApkArchiveInfo(id = testPkg.toPkgId(), versionCode = 1L)
        }
    }

    @After
    fun teardown() {
        appScope.cancel()
        workDir.deleteRecursively()
    }

    private fun inspector(root: Boolean = false, adb: Boolean = false) = AppInstallInspector(
        gatewaySwitch = gatewaySwitch,
        archiveService = ArchiveService(
            dispatcherProvider = dispatcherProvider,
            gatewaySwitchLazy = { gatewaySwitch },
            diskCache = ArchiveDiskCache(
                context = context,
                appScope = appScope,
                dispatcherProvider = dispatcherProvider,
            ),
            passwordStore = ArchivePasswordStore(),
        ),
        apkArchiveParser = apkArchiveParser,
        rootManager = mockk<RootManager> { every { useRoot } returns flowOf(root) },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(adb) },
        dispatcherProvider = dispatcherProvider,
    )

    /** Writes a zip whose entry names are exactly [entries], so unsanitized names can be tested. */
    private fun bundle(name: String, entries: Map<String, String>): LocalPath {
        val file = File(workDir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return LocalPath.build(file)
    }

    @Test
    fun `a plain apk is a single generated split`() = runTest2 {
        val apk = LocalPath.build(File(workDir, "plain.apk").apply { writeText("not really an apk") })

        val plan = inspector().inspect(apk)

        plan.format shouldBe AppInstallFormat.APK
        plan.pkgId shouldBe testPkg.toPkgId()
        plan.splits.map { it.stagedName } shouldContainExactly listOf("base.apk")
        plan.splits.single().size shouldBe apk.file.length()
        plan.obbEntries.shouldBeEmpty()
        plan.warnings.shouldBeEmpty()
    }

    @Test
    fun `a flat apks lists every root apk with the base first`() = runTest2 {
        val container = bundle(
            "flat.apks",
            mapOf(
                "split_config.arm64_v8a.apk" to "arm",
                "base.apk" to "base",
                "split_config.en.apk" to "en",
                "meta.sai_v2.json" to "{}",
            ),
        )

        val plan = inspector().inspect(container)

        plan.format shouldBe AppInstallFormat.APKS
        plan.splits.map { it.entryPath } shouldContainExactly listOf(
            "base.apk", "split_config.arm64_v8a.apk", "split_config.en.apk",
        )
        // Staged names are generated, never taken from the archive.
        plan.splits.map { it.stagedName } shouldContainExactly listOf(
            "base.apk", "split_0001.apk", "split_0002.apk",
        )
    }

    @Test
    fun `a bundletool apk set is rejected`() = runTest2 {
        val withToc = bundle("toc.apks", mapOf("toc.pb" to "binary", "base.apk" to "base"))
        shouldThrow<AppInstallUnsupportedApkSetException> { inspector().inspect(withToc) }

        val withVariantDirs = bundle(
            "dirs.apks",
            mapOf("splits/base-master.apk" to "base", "standalones/standalone-x86.apk" to "x86"),
        )
        shouldThrow<AppInstallUnsupportedApkSetException> { inspector().inspect(withVariantDirs) }
    }

    @Test
    fun `an apks holding only standalone variants is rejected`() = runTest2 {
        val container = bundle(
            "standalone.apks",
            mapOf("standalone-arm64.apk" to "a", "standalone-x86.apk" to "b"),
        )

        shouldThrow<AppInstallUnsupportedApkSetException> { inspector().inspect(container) }
    }

    @Test
    fun `an xapk manifest names the base apk`() = runTest2 {
        val container = bundle(
            "app.xapk",
            mapOf(
                "manifest.json" to """
                    {
                      "package_name": "$testPkg",
                      "version_code": "42",
                      "split_apks": [
                        {"file": "config.en.apk", "id": "config.en"},
                        {"file": "$testPkg.apk", "id": "base"}
                      ]
                    }
                """.trimIndent(),
                "config.en.apk" to "en",
                "$testPkg.apk" to "base",
            ),
        )

        val plan = inspector().inspect(container)

        plan.format shouldBe AppInstallFormat.XAPK
        plan.splits.first().entryPath shouldBe "$testPkg.apk"
        plan.warnings.shouldBeEmpty()
        // The base APK is parsed from inside the container, not the container itself.
        parsedPaths.single().name shouldBe "$testPkg.apk"
    }

    @Test
    fun `an xapk split in a subdirectory is installed`() = runTest2 {
        val container = bundle(
            "nested.xapk",
            mapOf(
                "manifest.json" to """
                    {
                      "package_name": "$testPkg",
                      "split_apks": [
                        {"file": "splits/config.en.apk", "id": "config.en"},
                        {"file": "base.apk", "id": "base"}
                      ]
                    }
                """.trimIndent(),
                "splits/config.en.apk" to "en",
                "base.apk" to "base",
            ),
        )

        val plan = inspector().inspect(container)

        plan.splits.map { it.entryPath } shouldContainExactly listOf("base.apk", "splits/config.en.apk")
    }

    @Test
    fun `an xapk missing a declared split is rejected`() = runTest2 {
        val container = bundle(
            "incomplete.xapk",
            mapOf(
                "manifest.json" to """
                    {
                      "package_name": "$testPkg",
                      "split_apks": [
                        {"file": "base.apk", "id": "base"},
                        {"file": "config.en.apk", "id": "config.en"}
                      ]
                    }
                """.trimIndent(),
                "base.apk" to "base",
            ),
        )

        shouldThrow<AppInstallUnsupportedBundleException> { inspector().inspect(container) }
    }

    @Test
    fun `an xapk without a manifest falls back to a filename scan`() = runTest2 {
        val container = bundle(
            "nomanifest.xapk",
            mapOf("base.apk" to "base", "config.de.apk" to "de"),
        )

        val plan = inspector().inspect(container)

        plan.warnings shouldContainExactly listOf(AppInstallPlan.Warning.NO_MANIFEST)
        plan.splits.map { it.entryPath } shouldContainExactly listOf("base.apk", "config.de.apk")
    }

    @Test
    fun `xapk expansion files are collected and warn about elevation`() = runTest2 {
        val container = bundle(
            "obb.xapk",
            mapOf(
                "manifest.json" to """{"package_name":"$testPkg"}""",
                "base.apk" to "base",
                "Android/obb/$testPkg/main.1.$testPkg.obb" to "payload",
            ),
        )

        val plan = inspector().inspect(container)

        plan.obbEntries.map { it.fileName } shouldContainExactly listOf("main.1.$testPkg.obb")
        plan.obbEntries.single().entryPath shouldBe "Android/obb/$testPkg/main.1.$testPkg.obb"
        plan.warnings shouldContainExactly listOf(
            AppInstallPlan.Warning.OBB_PRESENT,
            AppInstallPlan.Warning.OBB_NEEDS_ELEVATION,
        )

        // With root available the placement can actually succeed, so no elevation warning.
        inspector(root = true).inspect(container).warnings shouldContainExactly listOf(
            AppInstallPlan.Warning.OBB_PRESENT,
        )
    }

    @Test
    fun `an expansion the manifest also declares is only collected once`() = runTest2 {
        val obbPath = "Android/obb/$testPkg/main.obb"
        val container = bundle(
            "declared.xapk",
            mapOf(
                "manifest.json" to """
                    {"package_name":"$testPkg","expansions":[{"file":"$obbPath","install_path":"$obbPath"}]}
                """.trimIndent(),
                "base.apk" to "base",
                obbPath to "payload",
            ),
        )

        val plan = inspector().inspect(container)

        plan.obbEntries.map { it.fileName } shouldContainExactly listOf("main.obb")
    }

    @Test
    fun `a manifest expansion is placed under the name it declares`() = runTest2 {
        val container = bundle(
            "declaredpath.xapk",
            mapOf(
                "manifest.json" to """
                    {
                      "package_name":"$testPkg",
                      "expansions":[
                        {"file":"payload.obb","install_path":"Android/obb/$testPkg/main.1.obb"}
                      ]
                    }
                """.trimIndent(),
                "base.apk" to "base",
                "payload.obb" to "payload",
            ),
        )

        val plan = inspector().inspect(container)

        plan.obbEntries.single().entryPath shouldBe "payload.obb"
        plan.obbEntries.single().fileName shouldBe "main.1.obb"
    }

    @Test
    fun `expansions that would overwrite each other are rejected`() = runTest2 {
        val container = bundle(
            "colliding.xapk",
            mapOf(
                "manifest.json" to """{"package_name":"$testPkg"}""",
                "base.apk" to "base",
                "Android/obb/$testPkg/main.obb" to "one",
                "Android/obb/$testPkg/MAIN.OBB" to "two",
            ),
        )

        shouldThrow<AppInstallUnsupportedBundleException> { inspector().inspect(container) }
    }

    @Test
    fun `expansion entries outside the installed package are dropped`() = runTest2 {
        val container = bundle(
            "foreign.xapk",
            mapOf(
                "manifest.json" to """{"package_name":"$testPkg"}""",
                "base.apk" to "base",
                "Android/obb/com.someone.else/main.1.obb" to "foreign",
                "Android/obb/$testPkg/nested/deep.obb" to "too deep",
                "Android/obb/$testPkg/keep.obb" to "mine",
            ),
        )

        val plan = inspector().inspect(container)

        plan.obbEntries.map { it.fileName } shouldContainExactly listOf("keep.obb")
    }

    @Test
    fun `a container with traversal names is rejected`() = runTest2 {
        val container = bundle(
            "evil.xapk",
            mapOf(
                "manifest.json" to """{"package_name":"$testPkg"}""",
                "base.apk" to "base",
                "../escape.apk" to "escape",
                "../../Android/obb/$testPkg/escape.obb" to "escape",
            ),
        )

        // Installing what is left over would be an app assembled from the parts of a container that
        // tried to write outside itself.
        shouldThrow<AppInstallUnsupportedBundleException> { inspector().inspect(container) }
    }

    @Test
    fun `an absolute entry name is normalized rather than used as a destination`() = runTest2 {
        val container = bundle(
            "absolute.xapk",
            mapOf(
                "manifest.json" to """{"package_name":"$testPkg"}""",
                "base.apk" to "base",
                "/Android/obb/$testPkg/rooted.obb" to "payload",
            ),
        )

        val plan = inspector().inspect(container)

        plan.obbEntries.single().entryPath shouldBe "Android/obb/$testPkg/rooted.obb"
        plan.obbEntries.single().fileName shouldBe "rooted.obb"
        plan.splits.map { it.stagedName } shouldContainExactly listOf("base.apk")
    }

    @Test
    fun `an unusable entry size is recognized`() {
        val subject = inspector()
        subject.isUsableSize(entryMeta(size = 12L)) shouldBe true
        subject.isUsableSize(entryMeta(size = 0L)) shouldBe true
        subject.isUsableSize(entryMeta(size = -1L)) shouldBe false
        subject.isUsableSize(entryMeta(size = null)) shouldBe false
    }

    @Test
    fun `an apkm info file names the package`() = runTest2 {
        val container = bundle(
            "app.apkm",
            mapOf(
                "info.json" to """{"pname":"$testPkg","apk_title":"Example"}""",
                "$testPkg.apk" to "base",
                "split_config.xhdpi.apk" to "dpi",
            ),
        )

        val plan = inspector().inspect(container)

        plan.format shouldBe AppInstallFormat.APKM
        plan.splits.first().entryPath shouldBe "$testPkg.apk"
        plan.obbEntries.shouldBeEmpty()
    }

    @Test
    fun `an encrypted apkm is reported as protected`() = runTest2 {
        val payload = File(workDir, "base.apk").apply { writeText("base") }
        val info = File(workDir, "info.json").apply { writeText("""{"pname":"$testPkg"}""") }
        val container = File(workDir, "protected.apkm")
        net.lingala.zip4j.ZipFile(container, "hunter2".toCharArray()).use { zip ->
            val params = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            zip.addFile(payload, params)
            zip.addFile(info, params)
        }

        shouldThrow<AppInstallProtectedBundleException> { inspector().inspect(LocalPath.build(container)) }
    }

    @Test
    fun `a container without any apk is unsupported`() = runTest2 {
        val container = bundle("empty.apks", mapOf("readme.txt" to "nothing here"))

        shouldThrow<AppInstallUnsupportedBundleException> { inspector().inspect(container) }
    }

    private fun entryMeta(size: Long?) = ArchiveEntryMeta(
        segments = listOf("base.apk"),
        rawName = "base.apk",
        isDirectory = false,
        size = size,
        modifiedAt = null,
    )
}
