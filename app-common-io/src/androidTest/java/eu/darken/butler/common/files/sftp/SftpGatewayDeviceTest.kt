package eu.darken.butler.common.files.sftp

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * Runs Butler's SFTP gateway, connection pool, location manager and Keystore-backed credential store
 * on ART against tools/sftp-test-server.sh. `runBlocking`, not `runTest`: the connection timeouts are
 * real time.
 */
@RunWith(AndroidJUnit4::class)
class SftpGatewayDeviceTest {

    private lateinit var rig: SftpDeviceRig

    @Before
    fun setup() {
        rig = SftpDeviceRig(sftpTestEndpoint())
    }

    @After
    fun teardown() = runBlocking {
        if (::rig.isInitialized) rig.close()
    }

    private suspend fun write(path: SftpPath, content: ByteArray) =
        rig.gateway.openOutputStream(path).use { it.write(content) }

    private suspend fun read(path: SftpPath): ByteArray = rig.gateway.openInputStream(path).use { it.readBytes() }

    private suspend fun names(path: SftpPath): List<String> =
        rig.gateway.lookupFiles(path, LookupOptions()).map { it.name }

    @Test
    fun passwordLocationListsReadsWritesRenamesAndDeletes() = runBlocking<Unit> {
        val root = rig.passwordLocation()

        names(root) shouldContain "hello.txt"
        read(root.child("hello.txt")).decodeToString() shouldBe "Hello from Butler\n"

        val dir = root.child("gateway-device-${Uuid.random()}")
        rig.gateway.createDir(dir)
        try {
            rig.gateway.lookup(dir, LookupOptions()).fileType shouldBe FileType.DIRECTORY

            val content = "Written through the gateway on API ${Build.VERSION.SDK_INT}\n"
                .repeat(2000)
                .encodeToByteArray()
            val file = dir.child("written.txt")
            write(file, content)
            read(file).contentEquals(content) shouldBe true
            rig.gateway.lookup(file, LookupOptions()).size shouldBe content.size.toLong()

            val renamed = dir.child("renamed.txt")
            rig.gateway.move(file, renamed) shouldBe MoveOutcome.Moved
            names(dir) shouldContainExactly listOf("renamed.txt")
            read(renamed).contentEquals(content) shouldBe true

            rig.gateway.delete(renamed) shouldBe true
            names(dir) shouldBe emptyList()
        } finally {
            rig.gateway.delete(dir, recursive = true)
        }
        rig.gateway.exists(dir) shouldBe false
    }

    @Test
    fun keyLocationListsAndReads() = runBlocking<Unit> {
        val root = rig.keyLocation()

        names(root) shouldContain "docs"
        read(root.child("hello.txt")).decodeToString() shouldBe "Hello from Butler\n"
        read(root.child("docs", "readme.md")).decodeToString() shouldBe "# Seed\n\nFixed content for SFTP tests.\n"
    }

    @Test
    fun aChangedHostKeyIsReportedAsSuch() = runBlocking<Unit> {
        // A valid ed25519 key the server does not hold, standing in for a key pinned before a change.
        val stale = sftpTestPublicKey("user_ed25519.pub")
        val root = rig.passwordLocation(hostKey = stale)

        val error = shouldThrow<SftpHostKeyChangedException> { rig.gateway.lookupFiles(root, LookupOptions()) }
        error.locationId shouldBe root.locationId
        error.storedKey shouldBe TrustedHostKey.from(stale)
        error.presentedKey shouldBe TrustedHostKey.from(sftpTestPublicKey("host_ed25519.pub"))
    }
}
