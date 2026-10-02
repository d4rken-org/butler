package eu.darken.ssh

import eu.darken.ssh.OpenSshServer.Companion.KEY_USER
import eu.darken.ssh.OpenSshServer.Companion.PASSWORD
import eu.darken.ssh.OpenSshServer.Companion.PASSWORD_USER
import java.security.Security
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Shared behavior contract. Subclasses supply the implementation under test. */
@Tag("ssh-server")
internal abstract class SftpContractTest {
    protected abstract fun connector(config: SftpConfig = SftpConfig()): SftpConnector

    private val server: OpenSshServer
        get() = OpenSshServer.shared

    private fun password(user: String = PASSWORD_USER, secret: String = PASSWORD) =
        SshCredentials.Password(user, secret.toCharArray())

    private val pinned = HostKeyPolicy.Pinned(TestKeys.hostEd25519)

    private suspend fun session(config: SftpConfig = SftpConfig()): SftpSession =
        connector(config).connect(server.endpoint(), password(), pinned)

    private suspend fun SftpSession.scratch(): SftpPath =
        UPLOAD.child(Uuid.random().toString()).also { mkdir(it) }

    private suspend fun SftpSession.writeFile(path: SftpPath, content: ByteArray) {
        openFile(path, SftpOpenMode.CREATE_NEW).use {
            it.write(0, content)
            it.flush()
        }
    }

    private suspend fun SftpSession.readFile(path: SftpPath): ByteArray =
        openFile(path).use { file ->
            val result = ByteArray(file.size().toInt())
            var done = 0
            while (done < result.size) {
                val count = file.read(done.toLong(), result, done, result.size - done)
                assertTrue(count > 0)
                done += count
            }
            assertEquals(-1, file.read(result.size.toLong(), ByteArray(1)))
            result
        }

    @Test
    fun `password authentication leaves caller secrets intact and supports concurrent requests`(): Unit =
        runBlocking {
            val secret = PASSWORD.toCharArray()
            connector().connect(server.endpoint(), SshCredentials.Password(PASSWORD_USER, secret), pinned).use {
                assertArrayEquals(PASSWORD.toCharArray(), secret)
                secret.fill('\u0000')
                assertTrue(it.connected)
                coroutineScope {
                    (1..8)
                        .map { _ -> async { it.stat(UPLOAD) } }
                        .awaitAll()
                        .forEach { entry -> assertEquals(SftpFileType.DIRECTORY, entry.type) }
                }
            }
        }

    @ParameterizedTest
    @CsvSource(
        "user_ed25519, ed25519-passphrase",
        "user_rsa_pem, rsa-passphrase",
        "user_ecdsa_pkcs8, ''",
    )
    fun `key authentication accepts OpenSSH PEM and PKCS8 keys`(name: String, passphrase: String): Unit =
        runBlocking {
            val keyBytes = TestKeys.bytes(name)
            val secret = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
            val credentials = SshCredentials.PrivateKey(KEY_USER, keyBytes.copyOf(), secret)
            connector().connect(server.endpoint(), credentials, pinned).use {
                assertArrayEquals(keyBytes, credentials.keyBytes)
                assertEquals(passphrase.takeIf { p -> p.isNotEmpty() }, secret?.concatToString())
                assertEquals(SftpPath.Root, it.canonicalize("."))
            }
        }

    @Test
    fun `wrong password and unauthorized key fail authentication`(): Unit = runBlocking {
        assertFailure(SshException.Kind.AUTHENTICATION) {
            connector().connect(server.endpoint(), password(secret = "wrong"), pinned)
        }
        assertFailure(SshException.Kind.AUTHENTICATION) {
            connector()
                .connect(
                    server.endpoint(),
                    SshCredentials.PrivateKey(KEY_USER, TestKeys.bytes("host_ed25519")),
                    pinned,
                )
        }
        assertFailure(SshException.Kind.AUTHENTICATION) {
            connector().connect(server.endpoint(), password(user = KEY_USER, secret = ""), pinned)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "user_ed25519, wrong-passphrase",
        "user_ed25519, ''",
        "user_rsa_pem, wrong-passphrase",
        "user_rsa_pem, ''",
    )
    fun `wrong or missing passphrase is reported as such`(name: String, passphrase: String): Unit =
        runBlocking {
            assertFailure(SshException.Kind.KEY_PASSPHRASE) {
                connector()
                    .connect(
                        server.endpoint(),
                        SshCredentials.PrivateKey(
                            KEY_USER,
                            TestKeys.bytes(name),
                            passphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                        ),
                        pinned,
                    )
            }
        }

    @Test
    fun `unparseable keys are reported as key format failures`(): Unit = runBlocking {
        val ecdsa = TestKeys.bytes("user_ecdsa_pkcs8")
        val candidates =
            listOf(
                "not a key".encodeToByteArray(),
                ByteArray(0),
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0),
                ecdsa.copyOf(ecdsa.size / 2),
            )
        for (keyBytes in candidates) {
            assertFailure(SshException.Kind.KEY_FORMAT) {
                connector()
                    .connect(server.endpoint(), SshCredentials.PrivateKey(KEY_USER, keyBytes), pinned)
            }
        }
    }

    @Test
    fun `unknown policy refuses and carries the presented host key`(): Unit = runBlocking {
        val error = assertFailure(SshException.Kind.HOST_KEY_UNKNOWN) {
            connector().connect(server.endpoint(), password(), HostKeyPolicy.Unknown)
        }
        assertEquals(TestKeys.hostEd25519, error.presentedHostKey)
        assertEquals(TestKeys.HOST_ED25519_FINGERPRINT, error.presentedHostKey?.sha256Fingerprint)
    }

    @Test
    fun `pinned policy refuses any other key and accepts each pinned key type`(): Unit = runBlocking {
        val impostor = TestKeys.hostKey("user_ed25519.pub")
        val error = assertFailure(SshException.Kind.HOST_KEY_MISMATCH) {
            connector().connect(server.endpoint(), password(), HostKeyPolicy.Pinned(impostor))
        }
        assertEquals(TestKeys.hostEd25519, error.presentedHostKey)
        for (key in listOf(TestKeys.hostEd25519, TestKeys.hostRsa)) {
            connector().connect(server.endpoint(), password(), HostKeyPolicy.Pinned(key)).use {
                assertEquals(SftpFileType.DIRECTORY, it.stat(SftpPath.Root).type)
            }
        }
    }

    /**
     * sshd logs `Invalid user <name>` when it receives a user-authentication request for an
     * unknown account. The control connection proves such requests reach the log; the refused
     * connections must never produce one.
     */
    @Test
    fun `host key refusal happens before any user authentication request`(): Unit = runBlocking {
        val id = Uuid.random().toHexString().take(12)
        val unknown = "refused-unknown-$id"
        val mismatch = "refused-mismatch-$id"
        val control = "control-$id"
        assertFailure(SshException.Kind.HOST_KEY_UNKNOWN) {
            connector().connect(server.endpoint(), password(user = unknown), HostKeyPolicy.Unknown)
        }
        assertFailure(SshException.Kind.HOST_KEY_MISMATCH) {
            connector()
                .connect(
                    server.endpoint(),
                    password(user = mismatch),
                    HostKeyPolicy.Pinned(TestKeys.hostKey("user_ed25519.pub")),
                )
        }
        assertFailure(SshException.Kind.AUTHENTICATION) {
            connector().connect(server.endpoint(), password(user = control), pinned)
        }
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!server.logs.contains("Invalid user $control")) {
            assertTrue(deadline.hasNotPassedNow(), "Control authentication never reached the log")
            delay(50)
        }
        val logs = server.logs
        assertFalse(logs.contains(unknown), "Unknown-policy refusal sent user authentication")
        assertFalse(logs.contains(mismatch), "Mismatch refusal sent user authentication")
    }

    @Test
    fun `password login falls back to keyboard-interactive when the password method is disabled`(): Unit =
        runBlocking {
            val interactive = OpenSshServer.interactiveOnly
            connector().connect(interactive.endpoint(), password(), pinned).use {
                assertEquals(SftpFileType.DIRECTORY, it.stat(UPLOAD).type)
            }
            interactive.awaitLog("Accepted keyboard-interactive/pam for $PASSWORD_USER from")
        }

    @Test
    fun `wrong password over keyboard-interactive fails authentication`(): Unit = runBlocking {
        assertFailure(SshException.Kind.AUTHENTICATION) {
            connector().connect(OpenSshServer.interactiveOnly.endpoint(), password(secret = "wrong"), pinned)
        }
    }

    /**
     * sshd logs one `Failed <method> for invalid user <name>` line per password it receives. The
     * control connection runs after the probe, so once its line is in the log, all of the probe's are.
     */
    @Test
    fun `keyboard-interactive sends the password at most once per connection`(): Unit = runBlocking {
        val interactive = OpenSshServer.interactiveOnly
        val id = Uuid.random().toHexString().take(12)
        val probe = "kbdint-probe-$id"
        val control = "kbdint-control-$id"
        for (user in listOf(probe, control)) {
            assertFailure(SshException.Kind.AUTHENTICATION) {
                connector().connect(interactive.endpoint(), password(user = user), pinned)
            }
        }
        interactive.awaitLog("for invalid user $control from")
        val sent = Regex("Failed (password|keyboard-interactive\\S*) for invalid user $probe from")
        assertEquals(1, sent.findAll(interactive.logs).count(), "Password transmissions for $probe")
    }

    private suspend fun OpenSshServer.awaitLog(text: String) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!logs.contains(text)) {
            assertTrue(deadline.hasNotPassedNow(), "Never logged: $text")
            delay(50)
        }
    }

    @Test
    fun `connecting leaves the installed security providers untouched`(): Unit = runBlocking {
        val before = Security.getProviders().map { it.name }
        session().use { it.stat(SftpPath.Root) }
        assertEquals(before, Security.getProviders().map { it.name })
    }

    @Test
    fun `canonicalize resolves dot relative and absolute paths`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            assertEquals(SftpPath.Root, session.canonicalize("."))
            assertEquals(UPLOAD, session.canonicalize("upload"))
            assertEquals(dir, session.canonicalize("upload/${dir.segments.last()}"))
            assertEquals(UPLOAD, session.canonicalize("/upload/../upload/."))
            assertEquals(dir, session.canonicalize(dir.toString()))
            session.delete(dir)
        }
    }

    @Test
    fun `large directories list completely and repeatably`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val names = (0 until 1100).map { "entry-$it" }
            for (name in names) session.openFile(dir.child(name), SftpOpenMode.CREATE_NEW).close()
            val listing = session.list(dir)
            assertEquals(1, listing.take(1).toList().size)
            val entries = listing.toList()
            assertEquals(names.toSet(), entries.map { it.path.segments.last() }.toSet())
            assertEquals(names.size, entries.size)
            assertTrue(entries.all { it.type == SftpFileType.FILE && it.path.parent == dir })
            assertEquals(names.size, listing.toList().size)
            session.delete(dir, recursive = true)
            assertFailure(SshException.Kind.MISSING) { session.stat(dir) }
        }
    }

    @Test
    fun `stat follows links while lstat and list describe the link`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val target = dir.child("target.txt")
            session.writeFile(target, ByteArray(123) { 7 })
            val link = dir.child("link")
            session.symlink(link, "target.txt")
            val followed = session.stat(link)
            assertEquals(SftpFileType.FILE, followed.type)
            assertEquals(123L, followed.size)
            assertEquals(link, followed.path)
            assertEquals(SftpFileType.SYMLINK, session.lstat(link).type)
            val listed = session.list(dir).toList().associateBy { it.path }
            assertEquals(SftpFileType.SYMLINK, listed.getValue(link).type)
            assertEquals(SftpFileType.FILE, listed.getValue(target).type)
            assertEquals("target.txt", session.readLink(link))
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `dangling cyclic relative and absolute links resolve as the server sees them`(): Unit =
        runBlocking {
            session().use { session ->
                val dir = session.scratch()
                val target = dir.child("target.txt")
                session.writeFile(target, byteArrayOf(1, 2, 3))
                val sub = dir.child("sub")
                session.mkdir(sub)

                val dangling = dir.child("dangling")
                session.symlink(dangling, "missing-target")
                assertEquals(SftpFileType.SYMLINK, session.lstat(dangling).type)
                assertFailure(SshException.Kind.MISSING) { session.stat(dangling) }
                assertEquals("missing-target", session.readLink(dangling))

                val self = dir.child("self")
                session.symlink(self, ".")
                assertEquals(".", session.readLink(self))
                assertEquals(SftpFileType.SYMLINK, session.lstat(self).type)
                assertEquals(SftpFileType.DIRECTORY, session.stat(self).type)

                val ancestor = sub.child("up")
                session.symlink(ancestor, "../..")
                assertEquals(SftpFileType.DIRECTORY, session.stat(ancestor).type)
                assertEquals(SftpFileType.SYMLINK, session.lstat(ancestor).type)

                val relative = sub.child("relative")
                session.symlink(relative, "../target.txt")
                assertEquals("../target.txt", session.readLink(relative))
                assertEquals(3L, session.stat(relative).size)

                val absolute = dir.child("absolute")
                session.symlink(absolute, target.toString())
                assertEquals(target.toString(), session.readLink(absolute))
                assertArrayEquals(byteArrayOf(1, 2, 3), session.readFile(absolute))

                session.delete(dir, recursive = true)
                assertFailure(SshException.Kind.MISSING) { session.lstat(dir) }
            }
        }

    @Test
    fun `recursive delete unlinks directory links without touching their targets`(): Unit =
        runBlocking {
            session().use { session ->
                val outside = session.scratch()
                val kept = outside.child("kept.txt")
                session.writeFile(kept, byteArrayOf(42))
                session.mkdir(outside.child("nested"))
                val victim = session.scratch()
                session.symlink(victim.child("absolute"), outside.toString())
                session.symlink(victim.child("relative"), "../${outside.segments.last()}")
                session.mkdir(victim.child("real"))
                session.symlink(victim.child("real").child("deep"), outside.toString())
                session.delete(victim, recursive = true)
                assertFailure(SshException.Kind.MISSING) { session.lstat(victim) }
                assertArrayEquals(byteArrayOf(42), session.readFile(kept))
                assertEquals(SftpFileType.DIRECTORY, session.stat(outside.child("nested")).type)
                session.delete(outside, recursive = true)
            }
        }

    @Test
    fun `unusual but legal names round trip through create list and rename`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val names =
                listOf("back\\slash", "with space", "   ", " lead", "a:b", "trail.", "Grüße 日本語 🎉", "...")
            for ((index, name) in names.withIndex()) {
                session.writeFile(dir.child(name), byteArrayOf(index.toByte()))
            }
            assertEquals(names.toSet(), session.list(dir).toList().map { it.path.segments.last() }.toSet())
            for (name in names) session.rename(dir.child(name), dir.child("$name~"))
            assertEquals(
                names.map { "$it~" }.toSet(),
                session.list(dir).toList().map { it.path.segments.last() }.toSet(),
            )
            for ((index, name) in names.withIndex()) {
                assertArrayEquals(byteArrayOf(index.toByte()), session.readFile(dir.child("$name~")))
            }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `directories create and delete and refuse non-empty removal`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            assertEquals(SftpFileType.DIRECTORY, session.stat(dir).type)
            assertTrue(session.list(dir).toList().isEmpty())
            assertFailure(SshException.Kind.ALREADY_EXISTS) { session.mkdir(dir) }
            assertFailure(SshException.Kind.MISSING) { session.mkdir(dir.child("absent").child("x")) }
            val file = dir.child("file")
            session.writeFile(file, byteArrayOf(1))
            assertFailure(SshException.Kind.DIRECTORY_NOT_EMPTY) { session.delete(dir) }
            session.delete(file)
            assertFailure(SshException.Kind.MISSING) { session.stat(file) }
            repeat(3) { index ->
                val nested = dir.child("child$index").child("nested")
                session.mkdir(nested.parent!!)
                session.mkdir(nested)
                repeat(4) { session.writeFile(nested.child("file$it"), byteArrayOf(it.toByte())) }
            }
            assertFailure(SshException.Kind.DIRECTORY_NOT_EMPTY) { session.delete(dir) }
            session.delete(dir, recursive = true)
            assertFailure(SshException.Kind.MISSING) { session.stat(dir) }
            assertFailure(SshException.Kind.MISSING) { session.delete(dir) }
        }
    }

    @Test
    fun `rename moves entries and never replaces an existing destination`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val first = dir.child("first")
            val second = dir.child("second")
            session.writeFile(first, byteArrayOf(1))
            session.writeFile(second, byteArrayOf(2, 2))
            assertFailure(SshException.Kind.ALREADY_EXISTS) { session.rename(first, second) }
            val dangling = dir.child("dangling")
            session.symlink(dangling, "nowhere")
            assertFailure(SshException.Kind.ALREADY_EXISTS) { session.rename(first, dangling) }
            assertEquals(SftpFileType.SYMLINK, session.lstat(dangling).type)
            val sub1 = dir.child("sub1")
            val sub2 = dir.child("sub2")
            session.mkdir(sub1)
            session.mkdir(sub2)
            assertFailure(SshException.Kind.ALREADY_EXISTS) { session.rename(sub1, sub2) }
            assertArrayEquals(byteArrayOf(1), session.readFile(first))
            assertArrayEquals(byteArrayOf(2, 2), session.readFile(second))
            val moved = sub1.child("moved")
            session.rename(first, moved)
            assertFailure(SshException.Kind.MISSING) { session.stat(first) }
            assertArrayEquals(byteArrayOf(1), session.readFile(moved))
            val movedDir = dir.child("movedDir")
            session.rename(sub1, movedDir)
            assertEquals(SftpFileType.FILE, session.stat(movedDir.child("moved")).type)
            assertFailure(SshException.Kind.MISSING) { session.rename(first, dir.child("x")) }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `open modes create preserve and truncate`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val path = dir.child("file")
            assertFailure(SshException.Kind.MISSING) { session.openFile(path) }
            session.openFile(path, SftpOpenMode.CREATE_NEW).use { it.write(0, byteArrayOf(1, 2)) }
            assertFailure(SshException.Kind.ALREADY_EXISTS) {
                session.openFile(path, SftpOpenMode.CREATE_NEW)
            }
            session.openFile(path, SftpOpenMode.READ_WRITE).use { it.write(it.size(), byteArrayOf(3)) }
            assertArrayEquals(byteArrayOf(1, 2, 3), session.readFile(path))
            session.openFile(path, SftpOpenMode.OVERWRITE).use { assertEquals(0L, it.size()) }
            session.openFile(dir.child("fresh"), SftpOpenMode.READ_WRITE).use {
                assertEquals(0L, it.size())
            }
            session.openFile(dir.child("fresh2"), SftpOpenMode.OVERWRITE).close()
            assertEquals(SftpFileType.FILE, session.stat(dir.child("fresh2")).type)
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `reads and writes honor offsets EOF and the blocking view`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val path = dir.child("data")
            val payload = ByteArray(1024 * 1024 + 17).also { java.util.Random(42).nextBytes(it) }
            session.openFile(path, SftpOpenMode.CREATE_NEW).use { file ->
                file.write(0, payload, 3, payload.size - 6)
                file.write(0, ByteArray(0))
                file.flush()
                assertEquals(payload.size - 6L, file.size())
                val result = ByteArray(payload.size - 6)
                var done = 0
                while (done < result.size) {
                    val count = file.read(done.toLong(), result, done, result.size - done)
                    assertTrue(count > 0)
                    done += count
                }
                assertArrayEquals(payload.copyOfRange(3, payload.size - 3), result)
                assertEquals(4, file.read(result.size - 4L, ByteArray(10)))
                assertEquals(-1, file.read(result.size.toLong(), ByteArray(10)))
                assertEquals(0, file.read(result.size.toLong(), ByteArray(10), 0, 0))
                val buffer = ByteArray(8)
                assertEquals(3, file.read(1, buffer, 2, 3))
                assertArrayEquals(payload.copyOfRange(4, 7), buffer.copyOfRange(2, 5))
            }
            val small = dir.child("small")
            session.openFile(small, SftpOpenMode.CREATE_NEW).use {
                val blocking = it.blocking()
                blocking.write(2, byteArrayOf(99, 1, 2, 3, 99), 1, 3)
                blocking.flush()
                assertEquals(5L, it.size())
                val bytes = ByteArray(5)
                assertEquals(3, blocking.read(2, bytes, 1, 3))
                assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0), bytes)
                blocking.resize(3)
                assertEquals(3L, blocking.size())
                blocking.close()
                assertThrows(Exception::class.java) { blocking.size() }
            }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `positioned IO works beyond two GiB and resize truncates`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val path = dir.child("sparse")
            val offset = 3L * 1024 * 1024 * 1024
            val payload = byteArrayOf(1, 2, 3, 4)
            session.openFile(path, SftpOpenMode.READ_WRITE).use {
                it.write(offset, payload)
                assertEquals(offset + payload.size, it.size())
                val readBack = ByteArray(4)
                assertEquals(4, it.read(offset, readBack))
                assertArrayEquals(payload, readBack)
                assertEquals(-1, it.read(offset + 4, readBack))
                it.resize(offset - 10)
                assertEquals(offset - 10, it.size())
                it.resize(2)
                assertEquals(2L, it.size())
            }
            assertEquals(2L, session.stat(path).size)
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `timestamps permissions and ownership are reported and settable`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val file = dir.child("file")
            session.writeFile(file, byteArrayOf(1))
            val modified = Instant.fromEpochSeconds(1_700_000_000)
            session.setModifiedAt(file, modified)
            assertEquals(modified, session.stat(file).modifiedAt)
            session.setPermissions(file, "640".toInt(8))
            val entry = session.stat(file)
            assertEquals("640".toInt(8), entry.permissions)
            assertEquals(1001, entry.uid)
            assertNotNull(entry.gid)
            session.setPermissions(dir, "750".toInt(8))
            assertEquals("750".toInt(8), session.lstat(dir).permissions)
            assertFailure(SshException.Kind.MISSING) {
                session.setModifiedAt(dir.child("absent"), modified)
            }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `capacity describes the file system of the given path`(): Unit = runBlocking {
        session().use { session ->
            val capacity = session.capacity(UPLOAD)
            assertNotNull(capacity)
            capacity!!
            assertTrue(capacity.totalBytes > 0)
            assertTrue(capacity.freeBytes in 0..capacity.totalBytes)

            val dir = session.scratch()
            try {
                assertFailure(SshException.Kind.MISSING) { session.capacity(dir.child("absent")) }
            } finally {
                session.delete(dir, recursive = true)
            }
        }
    }

    @Test
    fun `failure kinds distinguish missing type and permission errors`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val file = dir.child("file")
            session.writeFile(file, byteArrayOf(1))
            assertFailure(SshException.Kind.MISSING) { session.stat(dir.child("absent")) }
            assertFailure(SshException.Kind.MISSING) { session.lstat(dir.child("absent").child("x")) }
            assertFailure(SshException.Kind.MISSING) { session.list(dir.child("absent")).toList() }
            assertFailure(SshException.Kind.MISSING) { session.readLink(dir.child("absent")) }
            assertFailure(SshException.Kind.NOT_DIRECTORY) { session.list(file).toList() }
            assertFailure(SshException.Kind.IS_DIRECTORY) { session.openFile(dir) }
            assertFailure(SshException.Kind.IS_DIRECTORY) { session.openFile(dir, SftpOpenMode.OVERWRITE) }
            assertFailure(SshException.Kind.ACCESS_DENIED) { session.mkdir(SftpPath.Root.child("forbidden")) }
            assertFailure(SshException.Kind.ACCESS_DENIED) {
                session.openFile(SftpPath.Root.child("forbidden"), SftpOpenMode.CREATE_NEW)
            }
            assertTrue(session.connected)
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `cancelling an in-flight read disconnects only its own session`(): Unit = runBlocking {
        StallingProxy(server.host, server.getMappedPort(22)).use { proxy ->
            session().use { other ->
                val dir = other.scratch()
                val path = dir.child("large")
                other.writeFile(path, ByteArray(4 * 1024 * 1024) { it.toByte() })
                val stalled = connector().connect(proxy.endpoint, password(), pinned)
                try {
                    val file = stalled.openFile(path)
                    proxy.stalled = true
                    val read = async(Dispatchers.Default) { file.read(0, ByteArray(4 * 1024 * 1024)) }
                    delay(500)
                    assertTrue(read.isActive, "Read completed despite the stalled transport")
                    val elapsed = measureTime { read.cancelAndJoin() }
                    assertTrue(elapsed < 5.seconds, "Cancellation took $elapsed")
                    assertFalse(stalled.connected)
                    assertFailure(SshException.Kind.TRANSPORT) { stalled.stat(SftpPath.Root) }
                } finally {
                    stalled.disconnect()
                }
                assertEquals(4L * 1024 * 1024, other.stat(path).size)
                session().use { fresh -> assertEquals(SftpFileType.FILE, fresh.stat(path).type) }
                other.delete(dir, recursive = true)
            }
        }
    }

    @Test
    fun `disconnect is immediate idempotent and safe from other threads`(): Unit = runBlocking {
        val session = session()
        assertTrue(session.connected)
        val threads = (1..4).map { Thread { session.disconnect() }.apply { start() } }
        threads.forEach { it.join(5000) }
        session.disconnect()
        assertFalse(session.connected)
        assertFailure(SshException.Kind.TRANSPORT) { session.stat(SftpPath.Root) }
        session.close()
    }

    private suspend fun assertFailure(
        kind: SshException.Kind,
        block: suspend () -> Unit,
    ): SshException {
        try {
            block()
        } catch (error: SshException) {
            assertEquals(kind, error.kind, "Unexpected failure: $error")
            return error
        }
        fail<Unit>("Expected $kind")
        throw AssertionError()
    }

    companion object {
        val UPLOAD: SftpPath = SftpPath.Root.child("upload")
    }
}
