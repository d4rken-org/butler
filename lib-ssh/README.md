# Kotlin SFTP

A Kotlin/JVM SFTP client API used by Butler. The implementation, `MinaSftpConnector`, runs
on Apache MINA SSHD 2.19; no MINA or Bouncy Castle type appears in the public API.
Production dependencies are Kotlin coroutines, MINA SSHD (`sshd-core`, `sshd-sftp`) and
Bouncy Castle (`bcprov`, plus `bcpkix` for encrypted PKCS#8 keys). The module has no
Android, Hilt, Okio, or Butler dependencies and targets JVM 17.

```kotlin
import eu.darken.ssh.*

val connector = MinaSftpConnector() // one per app; close() when done with SFTP
val password = obtainPassword() // CharArray owned by the caller
val session = try {
    connector.connect(
        SftpEndpoint(host = "nas.local"),
        SshCredentials.Password("alice", password),
        HostKeyPolicy.Pinned(storedHostKey),
    )
} catch (error: SshException) {
    // HOST_KEY_UNKNOWN / HOST_KEY_MISMATCH carry error.presentedHostKey for the user to confirm.
    throw error
} finally {
    password.fill('\u0000')
}
session.use {
    val home = it.canonicalize(".")
    it.list(home).collect { entry -> println("${entry.path} ${entry.type}") }
    it.openFile(home.child("notes.txt"), SftpOpenMode.CREATE_NEW).use { file ->
        file.write(0, "Hello".encodeToByteArray())
        file.flush()
    }
}
```

## Semantics

The caller owns every secret array (`password`, `keyBytes`, `passphrase`) and may erase it
once `connect` returns; the library copies what it needs and neither modifies nor retains
the caller's arrays. Private keys are parsed before any network I/O. An unparseable key
fails with `KEY_FORMAT`; a wrong or missing passphrase fails with `KEY_PASSPHRASE`.

`HostKeyPolicy` is applied during key exchange, before any user-authentication request is
sent. `Pinned` accepts only an equal key (type and blob) and otherwise fails with
`HOST_KEY_MISMATCH`; `Unknown` always fails with `HOST_KEY_UNKNOWN`. Both failures carry the
presented `HostKey`, whose `sha256Fingerprint` uses OpenSSH's `SHA256:<base64>` format.
There is no accept-all policy. A pinned key's type is offered first during negotiation, so a
server with several host keys presents the pinned one.

`SftpPath` is an absolute server path. A segment is invalid only if it is empty, `.`, `..`,
or contains `/` or NUL; backslashes, colons, whitespace-only names, trailing dots and
Unicode are ordinary characters. `canonicalize` sends SSH_FXP_REALPATH: `.` and relative
paths resolve against the server's initial directory. `readLink` returns the raw target,
which need not be a valid `SftpPath`.

`list` is a cold flow: each collection opens its own directory handle, and completion,
failure, or early collection closes it. Entries exclude `.` and `..` and describe links
themselves (lstat); `stat` follows links, `lstat` does not. Recursive `delete` unlinks
symbolic links and never descends through them, so a link's target is left untouched.
`rename` never replaces an existing destination and fails with `ALREADY_EXISTS`.

`CREATE_NEW` fails if the destination exists; `OVERWRITE` creates or truncates it.
`READ_WRITE` opens or creates without truncating. Writes use explicit offsets. A read may
be short and returns `-1` at EOF. A write sends the whole range or throws; writes are
pipelined, so a server rejection can surface from a later write, `size`, `resize`, `flush`
or `close` on the same handle. `flush` waits for every outstanding write acknowledgement.
It never sends `fsync@openssh.com` and makes no durability claim. `blocking()` exposes the
same handle for InputStream/Okio adapters on an I/O thread; closing either view closes the
handle. Do not race a handle's close against its operations. Timestamps have SFTP v3's
whole-second precision. `capacity(path)` uses `statvfs@openssh.com` on that path and
returns `null` when the server does not advertise the extension.

Generic `SSH_FX_FAILURE` replies map to `OTHER` unless a follow-up request disambiguates
them: `mkdir` or `rename` onto an existing path gives `ALREADY_EXISTS`, removing a non-empty
directory gives `DIRECTORY_NOT_EMPTY`, opening a directory gives `IS_DIRECTORY`, and listing
a file gives `NOT_DIRECTORY`.

Cancellation of an in-flight operation disconnects its owning session, releasing blocked
socket I/O and failing other requests on that session. Other sessions remain usable. Calls
have bounded connect/request timeouts; a request timeout also disconnects the session.
Mutations are never automatically replayed: timeout or cancellation may leave a partial
file or an operation whose server-side outcome is unknown. `disconnect()` is immediate,
thread-safe and idempotent. Butler owns pooling and retry policy outside this module.

## Backend and crypto setup

Butler runs this module on Android, where the JCA provider list is shared with the whole
app and must stay untouched. sshj cannot work that way: without adding Bouncy Castle to the
global provider list, every ed25519 path fails on API 28 and 36. MINA can, with the setup
in `MinaSetup.kt`, which every MINA entry point runs before any other MINA class:

- It sets the system property `org.apache.sshd.security.registrars` to `none`. The property
  is process-wide, and MINA reads it once, on its first crypto lookup, so it must be set
  before MINA initializes. MINA then registers none of its stock registrars, which would add
  Bouncy Castle globally and fail on Android (`No EC params for nistp384`).
- It registers one Bouncy Castle registrar that hands MINA a private `BouncyCastleProvider`
  instance. MINA calls `getInstance(algorithm, provider)` with it and never
  `Security.addProvider`. Ciphers and MACs stay with the platform's providers.
- MINA derives `~/.ssh` defaults from the user home, and Android has none. The home
  resolves to `/dev/null`, below which nothing exists.

If MINA's registration already ran with other registrars, the setup fails rather than run
on them. Code that uses MINA directly in the same process, such as the embedded test
server, must run the setup first.

A connector owns one MINA client, started by its first `connect`; `close()` stops it and
disconnects its sessions. The client reads no `~/.ssh` configuration, known hosts or
default identities, and writes no files. Passwords go on the wire from a wiped char copy.
MINA's key parsers take the passphrase as a `String`, which cannot be erased. An encrypted
key without a passphrase fails with `KEY_PASSPHRASE` before MINA's own check, which throws
`javax.security.auth.login.FailedLoginException`, a class Android does not ship. Certificate
host key algorithms are not offered.

Supported scope: SFTP version 3 over SSH-2 with password or public-key authentication
(OpenSSH, PEM and PKCS#8 key files; Ed25519, ECDSA and RSA). A password goes out once per
connection, over the `password` method if the server offers it and otherwise over
`keyboard-interactive`, which answers one hidden prompt with it and fails with
`AUTHENTICATION` on any other prompt. Agents, certificates, jump hosts and SFTP versions
above 3 are outside the implemented scope. Interoperability has been exercised against OpenSSH 9.2 and, for servers without
OpenSSH extensions, Apache MINA SSHD 2.19.

## Tests

Run from the repository root with its configured JDK:

```sh
./gradlew :lib-ssh:test
./gradlew :lib-ssh:contractTest
```

Unit tests need no server. `contractTest` requires Docker and runs three suites:

- `SftpContractTest` (tag `ssh-server`) against an `atmoz/sftp` container pinned by image
  digest, which runs Debian's OpenSSH `internal-sftp` chrooted into each user's home. Test
  users, host keys and user keys are fixed test-only files under `src/test/resources/keys`.
  Refusal before authentication is checked through sshd's `Invalid user` log line: a
  control connection proves the line appears, and refused connections must never produce it.
  A second container of the same image takes passwords only through PAM keyboard-interactive.
- `ExtensionlessSftpContractTest` (tag `sftp-embedded`) against an in-process Apache MINA
  SSHD server that advertises neither `statvfs@openssh.com` nor `fsync@openssh.com`.
- `SftpPerformanceTest` (tag `ssh-perf`): 64 MiB upload and download, listing 5,000 entries
  and 500 stats. Each scenario runs once to warm up and once measured on the same session.
  Results are printed and written to `lib-ssh/build/reports/ssh-perf/<connector>.json`
  (`mina.json`, `sshj.json`), with the warm-up pass as `warmupMillis`. There are no
  thresholds yet; the scenarios fail only on functional errors.

Each suite is an abstract class whose subclass supplies an `SftpConnector`: `MinaSftpContractTest`,
`MinaExtensionlessSftpContractTest` and `MinaSftpPerformanceTest` for the implementation, and the
`Sshj*` subclasses for `SshjSftpConnector`, a test-only sshj implementation kept as an oracle.
Both must pass the same assertions. Live contract tests bypass cached/up-to-date results.

The on-device smoke test, `SftpArtSmokeDeviceTest` in `:app-common-io`, runs this module on
ART against the same OpenSSH image:

```sh
ANDROID_SERIAL=<emulator> tools/sftp-device-test.sh
```
