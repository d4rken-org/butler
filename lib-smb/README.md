# Kotlin SMB

A Kotlin/JVM SMB client used by Butler. The protocol implementation lives in this
module; SMBJ 0.15.0 is a test-only reference implementation. Production dependencies
are Kotlin coroutines and Bouncy Castle's lightweight cryptographic primitives.
The module has no Android, Hilt, Okio, or Butler dependencies and targets JVM 17.

```kotlin
import eu.darken.smb.*
import kotlinx.coroutines.flow.flowOf

val password = obtainPassword() // CharArray owned by the caller
val share = try {
    KotlinSmbClient().connect(
        SmbEndpoint(host = "nas.local", share = "documents"),
        SmbCredentials.Password("alice", password),
    )
} finally {
    password.fill('\u0000')
}
share.use {
    val path = SmbPath.Root.child("notes.txt")
    it.writeChunks(path, flowOf("Hello".encodeToByteArray()))
    it.readChunks(path).collect { bytes -> consume(bytes) }
    it.list(SmbPath.Root).collect { entry -> println(entry.path) }
}
```

`list` and `readChunks` are cold flows: each collection opens its own handle, and
completion, failure, or early collection closes it. Each emitted chunk owns its
array. `writeChunks` returns the number of bytes written and flushes before closing.
`CREATE_NEW` fails if the destination exists; `OVERWRITE` creates or truncates it.
`READ_WRITE` opens or creates without truncating. Writes use explicit offsets;
append behavior requires obtaining `size()` and tracking the position, and is not
atomic against other writers. Rename never replaces an existing destination.

Use `openFile(...).use { ... }` for positioned reads, writes, resizing and flushing.
A read may be short and returns `-1` at EOF. A write completes the requested range
or throws. `blocking()` exposes the same handle for InputStream/Okio adapters on an
I/O thread; closing either view closes the handle. Do not race a handle's close
against its operations. Paths contain validated segments relative to the share.

Cancellation of an in-flight operation disconnects its owning share connection,
releasing blocked socket I/O and failing other requests on that connection. Other
connections remain usable. Calls have bounded connect/request timeouts. Mutations
are never automatically replayed: timeout or cancellation may leave a partial file
or an operation whose server-side outcome is unknown. `disconnect()` is immediate,
thread-safe and idempotent. Closing the share releases its server handles too.
Butler owns pooling and its read-retry policy outside this module.

Supported protocol scope:

- SMB 2.0.2, 2.1, 3.0, 3.0.2 and 3.1.1 over direct TCP.
- NTLMv2 password authentication and explicitly requested guest access.
- HMAC-SHA256/CMAC signing and SMB3 CCM/GCM encryption, including server-required
  encryption. Password sessions sign requests and require signed replies unless
  encrypted. `SmbConfig` can require signing or encryption; guest cannot satisfy
  either requirement. Password authentication cannot silently become guest.
- Paged directory enumeration, metadata, capacity, directory creation, deletion,
  rename, timestamps, sequential transfers and positioned files larger than 2 GiB.
- Concurrent requests routed by message ID and multi-credit transfers up to 1 MiB.

SMB1 is detected and rejected. DFS referrals, Kerberos, SMB compression, durable
handles, leases and oplocks are outside the implemented scope. Interoperability
has been exercised against Samba; Windows, macOS and other NAS servers have not
been verified.

## Tests

Run from the repository root with its configured JDK:

```sh
./gradlew :lib-smb:test
./gradlew :lib-smb:contractTest
./gradlew :app-common-io:testFossDebugUnitTest --tests '*smb*' --tests '*Smb*'
./gradlew :lib-smb:koverXmlReport :lib-smb:koverHtmlReport
```

Unit tests require no server. `contractTest` requires Docker and runs against
isolated Samba containers pinned by image digest. Coverage reports combine unit
and contract tests, so generating them also requires Docker. The HTML report is
at `lib-smb/build/reports/kover/html/index.html`, and XML at
`lib-smb/build/reports/kover/report.xml`.
`SmbContractTest` contains shared assertions executed by both `SmbjContractTest`
and `KotlinSmbContractTest`; `SmbjOracle` independently adapts the Java implementation.
Native security tests use a local proxy to corrupt signatures, data and encryption
tags, reorder replies and stall reads for cancellation.

The `SMB integration (Docker)` job in `.github/workflows/code-checks.yml` runs on
pull requests and pushes to main. It checks Docker availability, runs both library
suites with coverage, and runs `SmbGatewayIntegrationTest` through Butler's real
gateway against Samba. Testcontainers starts the servers with dynamic ports and
test credentials; no external server or repository secrets are needed. Live
contract tests bypass cached/up-to-date results, and the gateway task uses
`--rerun --no-build-cache`. The job fails if an expected test report is missing,
empty, failed, or skipped, or if the coverage report omits execution of the protocol
engine. It uploads JUnit, HTML test, and coverage reports and writes
line/branch coverage into the job summary. It covers gateway-to-server behavior;
Android UI automation and Windows/macOS server interoperability are separate gaps.
Malformed packets have targeted tests, but no broad parser fuzzing. Mid-transfer
reconnection is not covered end-to-end; the app's pool/retry behavior has unit tests.

The initial baseline on 2026-09-08 passed 11 tests against SMBJ before the Kotlin
protocol implementation existed: nine shared behavior tests and two wire/NTLMv2
contracts. Adding the Kotlin placeholder made all nine shared native cases fail.
The implementation then passed those same assertions, and the suite was expanded
with larger transfers, pagination, server security policies and tamper tests.

For extraction, move this module, declare the Kotlin JVM plugin version and replace
the root version-catalog aliases with standalone dependency coordinates. The test
oracle must remain test-only.
