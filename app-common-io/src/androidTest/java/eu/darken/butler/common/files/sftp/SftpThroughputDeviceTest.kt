package eu.darken.butler.common.files.sftp

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LookupOptions
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.MinaSftpConnector
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpOpenMode
import eu.darken.ssh.SftpPath
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.use
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

/**
 * Moves 16 MiB up and down on ART, once through lib-ssh directly and once through the gateway's
 * streams, and logs the rates under the `SftpThroughput` logcat tag. Only functional success is
 * asserted: emulator networking says little about real devices.
 */
@RunWith(AndroidJUnit4::class)
class SftpThroughputDeviceTest {

    private lateinit var endpoint: SftpEndpoint

    @Before
    fun setup() {
        endpoint = sftpTestEndpoint()
    }

    private fun report(path: String, direction: String, duration: Duration) {
        val mibPerSecond = PAYLOAD_SIZE / (1024.0 * 1024.0) / duration.toDouble(DurationUnit.SECONDS)
        Log.i(
            TAG,
            "API ${Build.VERSION.SDK_INT} $path $direction 16 MiB: ${duration.inWholeMilliseconds} ms, " +
                "%.1f MiB/s".format(Locale.ROOT, mibPerSecond),
        )
    }

    @Test
    fun libSsh() = runBlocking<Unit> {
        MinaSftpConnector().use { connector ->
            connector.connect(
                endpoint,
                SshCredentials.Password(SftpDeviceRig.PASSWORD_USER, SftpDeviceRig.PASSWORD.toCharArray()),
                HostKeyPolicy.Pinned(sftpTestPublicKey("host_ed25519.pub")),
            ).use { session ->
                val path = SftpPath(listOf("upload", "throughput-${Uuid.random()}.bin"))
                try {
                    val (_, upload) = measureTimedValue {
                        session.openFile(path, SftpOpenMode.CREATE_NEW).use { file ->
                            var offset = 0
                            while (offset < payload.size) {
                                val count = minOf(CHUNK, payload.size - offset)
                                file.write(offset.toLong(), payload, offset, count)
                                offset += count
                            }
                            file.flush()
                        }
                    }
                    report("lib-ssh", "upload", upload)

                    val digest = MessageDigest.getInstance("SHA-256")
                    val (total, download) = measureTimedValue {
                        session.openFile(path).use { file ->
                            val buffer = ByteArray(CHUNK)
                            var position = 0L
                            while (true) {
                                val count = file.read(position, buffer)
                                if (count == -1) break
                                digest.update(buffer, 0, count)
                                position += count
                            }
                            position
                        }
                    }
                    report("lib-ssh", "download", download)

                    total shouldBe PAYLOAD_SIZE.toLong()
                    digest.digest().contentEquals(payloadDigest) shouldBe true
                } finally {
                    session.delete(path)
                }
            }
        }
    }

    @Test
    fun gateway() = runBlocking<Unit> {
        val rig = SftpDeviceRig(endpoint)
        try {
            val root = rig.passwordLocation()
            // Connects the pool's session, which the lib-ssh measurement excludes as well.
            rig.gateway.lookup(root, LookupOptions())
            val file = root.child("throughput-${Uuid.random()}.bin")
            try {
                val (_, upload) = measureTimedValue {
                    rig.gateway.openOutputStream(file).use { out ->
                        var offset = 0
                        while (offset < payload.size) {
                            val count = minOf(CHUNK, payload.size - offset)
                            out.write(payload, offset, count)
                            offset += count
                        }
                    }
                }
                report("gateway", "upload", upload)
                rig.gateway.lookup(file, LookupOptions()).size shouldBe PAYLOAD_SIZE.toLong()

                val digest = MessageDigest.getInstance("SHA-256")
                val (total, download) = measureTimedValue {
                    rig.gateway.openInputStream(file).use { input ->
                        val buffer = ByteArray(CHUNK)
                        var read = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            digest.update(buffer, 0, count)
                            read += count
                        }
                        read
                    }
                }
                report("gateway", "download", download)

                total shouldBe PAYLOAD_SIZE.toLong()
                digest.digest().contentEquals(payloadDigest) shouldBe true
            } finally {
                rig.gateway.delete(file)
            }
        } finally {
            rig.close()
        }
    }

    companion object {
        private const val TAG = "SftpThroughput"
        private const val PAYLOAD_SIZE = 16 * 1024 * 1024
        private const val CHUNK = 256 * 1024
        private val payload: ByteArray by lazy { ByteArray(PAYLOAD_SIZE).also { Random(16).nextBytes(it) } }
        private val payloadDigest: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(payload) }
    }
}
