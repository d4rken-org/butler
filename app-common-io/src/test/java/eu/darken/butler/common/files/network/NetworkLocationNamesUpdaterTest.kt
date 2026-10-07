package eu.darken.butler.common.files.network

import android.content.Context
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.sftp.FakeSftpLocationManager
import eu.darken.butler.common.files.sftp.testSftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkLocationNamesUpdaterTest : BaseTest() {

    private class FakeSmbLocations : SmbLocationManager {
        override val locations = MutableStateFlow<List<SmbLocation>>(emptyList())
        override suspend fun get(id: Uuid) = locations.value.firstOrNull { it.id == id }
        override suspend fun create(
            label: String?, host: String, port: Int, share: String, basePath: Segments, domain: String?,
            username: String?, authType: SmbLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
        ): SmbLocation = throw NotImplementedError()

        override suspend fun update(
            id: Uuid, label: String?, host: String, port: Int, share: String, basePath: Segments, domain: String?,
            username: String?, authType: SmbLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
        ): SmbLocation = throw NotImplementedError()

        override suspend fun delete(id: Uuid) = throw NotImplementedError()
        override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) = throw NotImplementedError()
    }

    private val context = mockk<Context>()
    private val smbLocations = FakeSmbLocations()
    private val sftpLocations = FakeSftpLocationManager(emptyList())

    private val id = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val otherId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun smbLocation(id: Uuid, label: String?) = SmbLocation(
        id = id,
        label = label,
        host = "nas.local",
        share = "photos",
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun TestScope.startUpdater() {
        NetworkLocationNamesUpdater(backgroundScope, smbLocations, sftpLocations)
        runCurrent()
    }

    @AfterEach
    fun resetNames() = NetworkLocationNames.clear()

    @Test
    fun `SFTP names follow the stored locations`() = runTest {
        startUpdater()
        NetworkLocationNames.sftp(id) shouldBe null

        sftpLocations.put(testSftpLocation(id, host = "cnc-dev"))
        sftpLocations.put(testSftpLocation(otherId, host = "backup.lan"))
        runCurrent()
        NetworkLocationNames.sftp(id)!!.get(context) shouldBe "cnc-dev"
        NetworkLocationNames.sftp(otherId)!!.get(context) shouldBe "backup.lan"

        sftpLocations.put(testSftpLocation(id, host = "cnc-dev").copy(label = "Build box"))
        runCurrent()
        NetworkLocationNames.sftp(id)!!.get(context) shouldBe "Build box"

        sftpLocations.byId.value = sftpLocations.byId.value - id
        runCurrent()
        NetworkLocationNames.sftp(id) shouldBe null
        NetworkLocationNames.sftp(otherId)!!.get(context) shouldBe "backup.lan"
    }

    @Test
    fun `SMB names follow the stored locations`() = runTest {
        startUpdater()
        NetworkLocationNames.smb(id) shouldBe null

        smbLocations.locations.value = listOf(smbLocation(id, label = "Home NAS"), smbLocation(otherId, label = null))
        runCurrent()
        NetworkLocationNames.smb(id)!!.get(context) shouldBe "Home NAS"
        NetworkLocationNames.smb(otherId)!!.get(context) shouldBe "photos"

        smbLocations.locations.value = listOf(smbLocation(id, label = "Attic NAS"), smbLocation(otherId, label = null))
        runCurrent()
        NetworkLocationNames.smb(id)!!.get(context) shouldBe "Attic NAS"

        smbLocations.locations.value = listOf(smbLocation(otherId, label = null))
        runCurrent()
        NetworkLocationNames.smb(id) shouldBe null
        NetworkLocationNames.smb(otherId)!!.get(context) shouldBe "photos"
    }

    @Test
    fun `the same id names different locations per protocol`() = runTest {
        startUpdater()

        smbLocations.locations.value = listOf(smbLocation(id, label = "Home NAS"))
        sftpLocations.put(testSftpLocation(id, host = "cnc-dev"))
        runCurrent()
        NetworkLocationNames.smb(id)!!.get(context) shouldBe "Home NAS"
        NetworkLocationNames.sftp(id)!!.get(context) shouldBe "cnc-dev"

        smbLocations.locations.value = emptyList()
        runCurrent()
        NetworkLocationNames.smb(id) shouldBe null
        NetworkLocationNames.sftp(id)!!.get(context) shouldBe "cnc-dev"
    }
}
