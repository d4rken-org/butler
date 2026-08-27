package eu.darken.butler.common.storage.saf

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    fun `third party provider with a launcher is suggested`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = "Tabby"))

        suggester.getSuggestions() shouldBe listOf(
            StorageProviderSuggestion(
                packageName = "com.tabby",
                authority = "com.tabby.documents",
                label = "Tabby",
                known = null,
            )
        )
    }

    @Test
    fun `our own provider is excluded`() = runTest {
        val suggester = create(FakeProvider(pkg = OUR_PKG, authority = "$OUR_PKG.provider.documents"))

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `platform providers are excluded`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.android.externalstorage", authority = "com.android.externalstorage.documents"),
            FakeProvider(pkg = "com.android.providers.downloads", authority = "com.android.providers.downloads.documents"),
            FakeProvider(pkg = "com.android.providers.media.module", authority = "com.android.providers.media.documents"),
            FakeProvider(pkg = "com.android.mtp", authority = "com.android.mtp.documents"),
            FakeProvider(pkg = "com.android.documentsui", authority = "com.android.documentsui.archives"),
            FakeProvider(pkg = "com.google.android.documentsui", authority = "com.android.documentsui.archives"),
        )

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `provider without a launcher entry is excluded`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.android.shell", authority = "com.android.shell.documents", hasLauncher = false),
        )

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `non exported provider is excluded`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", exported = false),
        )

        suggester.getSuggestions().shouldBeEmpty()
    }

    @Test
    fun `curated providers are tagged and sorted first`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = "Tabby"),
            FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"),
        )

        val suggestions = suggester.getSuggestions()

        suggestions.map { it.packageName } shouldBe listOf("com.termux", "com.tabby")
        suggestions[0].known shouldBe KnownStorageProvider.TERMUX
        suggestions[1].known.shouldBeNull()
    }

    @Test
    fun `a failing provider does not remove the others`() = runTest {
        val suggester = create(
            FakeProvider(pkg = "com.broken", authority = "com.broken.documents", label = null),
            FakeProvider(pkg = "com.tabby", authority = "com.tabby.documents", label = "Tabby"),
        )

        suggester.getSuggestions().map { it.packageName } shouldBe listOf("com.tabby")
    }

    @Test
    fun `label lookup resolves a third party authority`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"))

        suggester.labelForAuthority("com.termux.documents") shouldBe "Termux"
    }

    @Test
    fun `label lookup returns null for an unknown authority`() = runTest {
        val suggester = create(FakeProvider(pkg = "com.termux", authority = "com.termux.documents", label = "Termux"))

        suggester.labelForAuthority("com.example.documents").shouldBeNull()
    }

    @Test
    fun `label lookup returns null for a platform authority`() = runTest {
        val suggester = create(
            FakeProvider(
                pkg = "com.android.externalstorage",
                authority = "com.android.externalstorage.documents",
                label = "External Storage",
            ),
        )

        suggester.labelForAuthority("com.android.externalstorage.documents").shouldBeNull()
    }

    companion object {
        private const val OUR_PKG = "eu.darken.butler.debug"
    }
}
