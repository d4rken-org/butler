package eu.darken.butler.common.files.smb

import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * Pins the identity hash of every SMB schema version that has been exported.
 *
 * Room stores this hash inside the database and refuses to open one whose hash disagrees with the
 * build's. CI already fails when a schema JSON is left uncommitted, but it cannot tell a NEW version
 * from an EDITED one: regenerating `1.json` in place and committing it passes every existing check
 * while making every installed database unopenable. Freezing the hashes here is what catches that.
 *
 * A failure means an already-exported version changed. The fix is a version bump plus a migration,
 * never a new expected hash - except for a version that has genuinely never shipped, and this branch
 * is where that judgement gets made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbSchemaIdentityTest : BaseTest() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun identityHash(database: String, version: Int): String {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val raw = assets.open("$database/$version.json").bufferedReader().use { it.readText() }
        return json.parseToJsonElement(raw)
            .jsonObject.getValue("database")
            .jsonObject.getValue("identityHash")
            .jsonPrimitive.content
    }

    @Test
    fun `the network location schema versions are unchanged`() {
        identityHash(LOCATIONS, 1) shouldBe "eb27303b4a3a1e72d78484ceddc62669"
        identityHash(LOCATIONS, 2) shouldBe "1ad05dcbfce071dad7fd762b538468fa"
    }

    @Test
    fun `the credential schema versions are unchanged`() {
        identityHash(CREDENTIALS, 1) shouldBe "ca94bee1fa1b7ad4e437aa8ee2738863"
    }

    companion object {
        private const val LOCATIONS = "eu.darken.butler.common.files.smb.location.db.SmbLocationDatabase"
        private const val CREDENTIALS = "eu.darken.butler.common.files.smb.credentials.db.SmbCredentialDatabase"
    }
}
