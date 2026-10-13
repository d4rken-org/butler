package eu.darken.butler.common.files.webdav.location.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.webdav.testCertificate
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The compare-and-set statements against real SQLite, which the manager's fake DAO only imitates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavLocationsDaoTest : BaseTest() {

    private lateinit var database: WebDavLocationDatabase
    private lateinit var dao: WebDavLocationsDao

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val url = "https://cloud.example.com/remote.php/dav/files/alice/"
    private val oldCert = testCertificate(1)
    private val newCert = testCertificate(2)

    private val row = WebDavLocationEntity(
        locationId = locationId,
        label = "Cloud",
        url = url,
        authType = "BASIC",
        username = "alice",
        rememberCredential = true,
        credentialVersion = 1,
        certificateDer = oldCert.der,
        certificateFingerprint = oldCert.fingerprint,
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000),
    )

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            WebDavLocationDatabase::class.java,
        ).build()
        dao = database.webDavLocations()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun retrust(url: String = this.url, expectedTrustRevision: Int = 1) = dao.retrust(
        locationId = locationId,
        url = url,
        expectedTrustRevision = expectedTrustRevision,
        certificateDer = newCert.der,
        certificateFingerprint = newCert.fingerprint,
        updatedAt = Instant.fromEpochMilliseconds(2_000),
    )

    @Test
    fun `a row without a pin round trips`() = runTest {
        val anonymous = row.copy(
            authType = "ANONYMOUS",
            username = null,
            rememberCredential = false,
            certificateDer = null,
            certificateFingerprint = null,
        )

        dao.upsert(anonymous)

        dao.get(locationId) shouldBe anonymous
    }

    @Test
    fun `retrust replaces the pin of the URL it was confirmed for`() = runTest {
        dao.upsert(row)

        retrust() shouldBe 1

        val stored = dao.get(locationId)!!
        stored.certificateDer.contentEquals(newCert.der) shouldBe true
        stored.certificateFingerprint shouldBe newCert.fingerprint
        stored.trustRevision shouldBe 2
        stored.updatedAt shouldBe Instant.fromEpochMilliseconds(2_000)
    }

    @Test
    fun `retrust pins a certificate where nothing was pinned`() = runTest {
        dao.upsert(row.copy(certificateDer = null, certificateFingerprint = null))

        retrust() shouldBe 1

        dao.get(locationId)!!.certificateFingerprint shouldBe newCert.fingerprint
    }

    @Test
    fun `retrust changes nothing once the URL was edited`() = runTest {
        dao.upsert(row.copy(url = "https://cloud.example.com/remote.php/dav/files/bob/"))

        retrust() shouldBe 0

        dao.get(locationId) shouldBe row.copy(url = "https://cloud.example.com/remote.php/dav/files/bob/")
    }

    @Test
    fun `retrust changes nothing once the pin was replaced`() = runTest {
        dao.upsert(row.copy(trustRevision = 2))

        retrust(expectedTrustRevision = 1) shouldBe 0

        dao.get(locationId) shouldBe row.copy(trustRevision = 2)
    }

    @Test
    fun `a sighting is only recorded while the endpoint still matches`() = runTest {
        dao.upsert(row)

        dao.markSeen(locationId, Instant.fromEpochMilliseconds(3_000)) { false }
        dao.get(locationId)!!.lastSeenAt shouldBe null

        dao.markSeen(locationId, Instant.fromEpochMilliseconds(4_000)) { it.url == url }
        dao.get(locationId)!!.lastSeenAt shouldBe Instant.fromEpochMilliseconds(4_000)
    }

    @Test
    fun `a sighting of an unknown location writes nothing`() = runTest {
        dao.markSeen(locationId, Instant.fromEpochMilliseconds(3_000)) { true }

        dao.get(locationId) shouldBe null
    }

    @Test
    fun `rewrite stores the transformed row`() = runTest {
        dao.upsert(row)

        dao.rewrite(locationId) { it.copy(label = "Renamed") }!!.label shouldBe "Renamed"
        dao.get(locationId)!!.label shouldBe "Renamed"
    }

    @Test
    fun `rewrite of an unknown location writes nothing`() = runTest {
        dao.rewrite(locationId) { it.copy(label = "Renamed") } shouldBe null
        dao.get(locationId) shouldBe null
    }
}
