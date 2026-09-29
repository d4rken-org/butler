package eu.darken.butler.common.files.sftp.location.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.sftp.testHostKey
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
class SftpLocationsDaoTest : BaseTest() {

    private lateinit var database: SftpLocationDatabase
    private lateinit var dao: SftpLocationsDao

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val oldKey = testHostKey(1)
    private val newKey = testHostKey(2)

    private val row = SftpLocationEntity(
        locationId = locationId,
        label = "NAS",
        host = "nas.local",
        port = 22,
        username = "darken",
        basePath = "",
        authType = "PASSWORD",
        rememberCredential = true,
        credentialVersion = 1,
        hostKeyType = oldKey.type,
        hostKeyBlob = oldKey.blob,
        hostKeyFingerprint = oldKey.fingerprint,
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000),
    )

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SftpLocationDatabase::class.java,
        ).build()
        dao = database.sftpLocations()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `retrust replaces the pin of the endpoint it was confirmed for`() = runTest {
        dao.upsert(row)

        dao.retrust(
            locationId = locationId,
            host = "nas.local",
            port = 22,
            expectedTrustRevision = 1,
            hostKeyType = newKey.type,
            hostKeyBlob = newKey.blob,
            hostKeyFingerprint = newKey.fingerprint,
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        ) shouldBe 1

        val stored = dao.get(locationId)!!
        stored.hostKeyBlob.contentEquals(newKey.blob) shouldBe true
        stored.hostKeyFingerprint shouldBe newKey.fingerprint
        stored.trustRevision shouldBe 2
        stored.updatedAt shouldBe Instant.fromEpochMilliseconds(2_000)
    }

    @Test
    fun `retrust changes nothing once the endpoint was edited`() = runTest {
        dao.upsert(row.copy(host = "other.nas"))

        dao.retrust(
            locationId = locationId,
            host = "nas.local",
            port = 22,
            expectedTrustRevision = 1,
            hostKeyType = newKey.type,
            hostKeyBlob = newKey.blob,
            hostKeyFingerprint = newKey.fingerprint,
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        ) shouldBe 0

        dao.get(locationId) shouldBe row.copy(host = "other.nas")
    }

    @Test
    fun `retrust changes nothing once the port was edited`() = runTest {
        dao.upsert(row.copy(port = 2222))

        dao.retrust(
            locationId = locationId,
            host = "nas.local",
            port = 22,
            expectedTrustRevision = 1,
            hostKeyType = newKey.type,
            hostKeyBlob = newKey.blob,
            hostKeyFingerprint = newKey.fingerprint,
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        ) shouldBe 0

        dao.get(locationId)!!.trustRevision shouldBe 1
    }

    @Test
    fun `retrust changes nothing once the pin was replaced`() = runTest {
        dao.upsert(row.copy(trustRevision = 2))

        dao.retrust(
            locationId = locationId,
            host = "nas.local",
            port = 22,
            expectedTrustRevision = 1,
            hostKeyType = newKey.type,
            hostKeyBlob = newKey.blob,
            hostKeyFingerprint = newKey.fingerprint,
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        ) shouldBe 0

        dao.get(locationId) shouldBe row.copy(trustRevision = 2)
    }

    @Test
    fun `a sighting is only recorded for the endpoint it was seen at`() = runTest {
        dao.upsert(row)

        dao.markSeen(locationId, "other.nas", 22, Instant.fromEpochMilliseconds(3_000))
        dao.get(locationId)!!.lastSeenAt shouldBe null

        dao.markSeen(locationId, "nas.local", 22, Instant.fromEpochMilliseconds(4_000))
        dao.get(locationId)!!.lastSeenAt shouldBe Instant.fromEpochMilliseconds(4_000)
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
