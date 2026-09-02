package eu.darken.butler.common.storage.saf

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StorageProviderSuggesterTest : BaseTest() {

    /** [label] null makes the label lookup throw, i.e. this entry fails while the others don't. */
    private data class FakeProvider(
        val pkg: String,
        val authority: String,
        val label: String? = "Label",
        val exported: Boolean = true,
        val hasLauncher: Boolean = true,
    )

    private fun FakeProvider.toResolveInfo() = ResolveInfo().apply {
        providerInfo = ProviderInfo().apply {
            authority = this@toResolveInfo.authority
            exported = this@toResolveInfo.exported
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    private fun create(vararg providers: FakeProvider): StorageProviderSuggester {
        val fakes = providers.toList()
        val resolveInfos = fakes.map { it.toResolveInfo() }
        val pm = mockk<PackageManager>()
        every { pm.queryIntentContentProviders(any<Intent>(), any<Int>()) } returns resolveInfos
        every { pm.getLaunchIntentForPackage(any()) } answers {
            val pkg = firstArg<String>()
            if (fakes.any { it.pkg == pkg && it.hasLauncher }) Intent() else null
        }
        every { pm.getApplicationLabel(any()) } answers {
            val pkg = firstArg<ApplicationInfo>().packageName
            fakes.first { it.pkg == pkg }.label ?: throw IllegalStateException("No label for $pkg")
        }
        every { pm.getPackageInfo(any<String>(), any<Int>()) } answers {
            PackageInfo().apply {
                packageName = firstArg()
                lastUpdateTime = UPDATED_AT
            }
        }
        every { pm.resolveContentProvider(any<String>(), any<Int>()) } answers {
            val authority = firstArg<String>()
            resolveInfos.firstOrNull { it.providerInfo.authority == authority }?.providerInfo
        }
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { context.packageName } returns OUR_PKG

        return StorageProviderSuggester(context, TestDispatcherProvider())
    }

    @Test
    fun `curated provider with a launcher is suggested`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"))

        suggester.getSuggestions() shouldBe listOf(
            StorageProviderSuggestion(
                app = StorageProviderApp(packageName = "com.termux", appLabel = "Termux", lastUpdateTime = UPDATED_AT),
                authority = "com.termux.documents",
                known = KnownStorageProvider.TERMUX,
            )
        )
    }

    @Test
    fun `uncurated third party provider is not suggested`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = "Tabby"))

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `curated app's other providers are not suggested`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.termux", authority = "com.termux.files", label = "Termux"),
            FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"),
        )

        suggester.getSuggestions().map { it.authority } shouldBe listOf("com.termux.documents")
    }

    @Test
    fun `curated provider without a launcher entry is excluded`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.termux", authority = "com.termux.documents", hasLauncher = false),
        )

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `non exported curated provider is excluded`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.termux", authority = "com.termux.documents", exported = false),
        )

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `a failing curated provider is skipped`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = null))

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `app lookup resolves a third party authority`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = "Tabby"))

        suggester.appForAuthority("com.tabby.documents") shouldBe StorageProviderApp(
            packageName = "com.tabby",
            appLabel = "Tabby",
            lastUpdateTime = UPDATED_AT,
        )
    }

    @Test
    fun `app lookup returns null for a failing provider`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = null))

        suggester.appForAuthority("com.tabby.documents").shouldBeNull()
    }

    @Test
    fun `app lookup returns null for an unknown authority`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"))

        suggester.appForAuthority("com.example.documents").shouldBeNull()
    }

    @Test
    fun `app lookup returns null for our own provider`() = runTest {
        val suggester = create(FakeProvider(pkg = OUR_PKG, authority = "$OUR_PKG.provider.documents"))

        suggester.appForAuthority("$OUR_PKG.provider.documents").shouldBeNull()
    }

    @Test
    fun `app lookup returns null for platform authorities`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.android.externalstorage", authority = "com.android.externalstorage.documents"),
            FakeProvider(pkg = "com.android.providers.downloads", authority = "com.android.providers.downloads.documents"),
            FakeProvider(pkg = "com.android.providers.media.module", authority = "com.android.providers.media.documents"),
            FakeProvider(pkg = "com.android.mtp", authority = "com.android.mtp.documents"),
            FakeProvider(pkg = "com.android.documentsui", authority = "com.android.documentsui.archives"),
        )

        listOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            "com.android.mtp.documents",
            "com.android.documentsui.archives",
        ).forEach { suggester.appForAuthority(it).shouldBeNull() }
    }

    @Test
    fun `app lookup returns null for a provider without a launcher entry`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.android.shell", authority = "com.android.shell.documents", hasLauncher = false),
        )

        suggester.appForAuthority("com.android.shell.documents").shouldBeNull()
    }

    @Test
    fun `app lookup returns null for a non exported provider`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", exported = false),
        )

        suggester.appForAuthority("com.tabby.documents").shouldBeNull()
    }

    companion object {
        private const val OUR_PKG = "eu.darken.butler.debug"
        private const val UPDATED_AT = 1000L
    }
}
