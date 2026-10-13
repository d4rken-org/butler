# Kotlin WebDAV

A Kotlin/JVM WebDAV client API used by Butler. The implementation, `OkHttpWebDavConnector`,
runs on OkHttp 5.4 and the JDK's SAX parser; no OkHttp type appears in the public API.
Production dependencies are Kotlin coroutines and OkHttp. The module has no Android, Hilt or
Butler dependencies and targets JVM 17.

```kotlin
import eu.darken.webdav.*

val connector = OkHttpWebDavConnector()
val password = obtainPassword() // CharArray owned by the caller
val session = try {
    connector.connect(
        WebDavEndpoint("https://cloud.example/remote.php/dav/files/alice/"),
        WebDavCredentials.Basic("alice", password),
        CertificatePolicy.Pinned(storedCertificate),
    )
} catch (error: WebDavException) {
    // CERTIFICATE_UNTRUSTED / CERTIFICATE_MISMATCH carry error.presentedCertificate for the user to confirm.
    throw error
} finally {
    password.fill('\u0000')
}
session.use {
    it.list(WebDavPath.Root).collect { entry -> println("${entry.path} ${entry.size}") }
    val bytes = "Hello".encodeToByteArray()
    it.openWrite(WebDavPath.Root.child("notes.txt"), bytes.size.toLong(), createOnly = true).use { upload ->
        upload.write(bytes)
    }
}
```

## Semantics

`WebDavEndpoint` accepts absolute `http`/`https` URLs without userinfo, query or fragment and
normalizes them to a collection URL ending in `/`. `WebDavPath` is relative to it; a segment is
invalid only if it is empty, `.`, `..`, or contains `/` or NUL. Each segment is percent-encoded on
its own, so `%`, `?`, `#` and `\` are ordinary characters. Multistatus hrefs map back to paths
whether the server sends absolute paths, full URLs (the host is ignored), percent-encoded or raw
names, with or without trailing slashes. Entries outside the listed collection are dropped, and
`list` excludes the collection itself.

`connect` sends `PROPFIND Depth: 0` to the endpoint, so authentication, certificate and endpoint
problems surface there, and a non-collection endpoint fails with `NOT_DIRECTORY`. Each session owns
its OkHttp client, connection pool and TLS context. Basic credentials are encoded once per session
and sent preemptively on every request to the endpoint's origin (scheme, host and port), never in
response to a challenge: a streaming PUT body cannot be replayed after a 401. The caller may erase
the password array once `connect` returns; the encoded header is an immutable String held for the
session's lifetime. Digest authentication is not supported.

`CertificatePolicy` is applied in the TLS handshake, before any request is sent, and ignored for
`http://`. `System` accepts chains the platform trusts for the endpoint's host name. `Pinned` also
accepts a leaf equal to the pin, without checking the host name. Anything else fails with
`CERTIFICATE_UNTRUSTED` (`System`) or `CERTIFICATE_MISMATCH` (`Pinned`), including a trusted chain
for another host name. Both carry the presented leaf as a `ServerCertificate`, whose
`sha256Fingerprint` is uppercase hex separated by colons. The leaf travels in the handshake
exception's cause chain, so concurrent connections never report each other's certificate.

Redirects are handled by the library. PROPFIND and GET follow up to five redirects within the
endpoint's origin; a redirect to another host, port or scheme, including an HTTPS-to-HTTP downgrade,
fails with `PROTOCOL` without contacting the target. MKCOL, DELETE, MOVE and COPY follow one
redirect only when its `Location` is the request URL plus `/` (Apache's `DirectorySlash` for
collections); every other redirect of a mutation, and any redirect of a PUT, fails with `PROTOCOL`.

Mutations (PUT, MKCOL, DELETE, MOVE, COPY) are never retried after a connection failure, because a
lost response does not prove the mutation failed. PROPFIND and GET use OkHttp's connection retry.
Cancelling a coroutine cancels its HTTP call. `disconnect()` cancels every call of the session and
evicts its connections. Idle connections are dropped after 4 seconds, below Apache's default 5 s
keep-alive, so a mutation rarely starts on a connection the server is closing.

`delete` is recursive by protocol. `move` and `copy` send `Overwrite: F` and fail with
`ALREADY_EXISTS` instead of replacing a destination; `copy` sends `Depth: infinity`. A 207 response
to DELETE, MOVE or COPY fails with the first per-resource error status.

`openRead` sends `Accept-Encoding: identity` and, for an offset, `Range: bytes=<offset>-`. A 206 must
start at that offset, otherwise it fails with `PROTOCOL`. A 200 to a ranged request reports
`rangeHonored = false` and its body starts at 0. A 416 at or beyond the end is an empty download;
`totalLength` is `null` when the server omits `Content-Range`. With `expectedEtag`, a strong ETag is
sent as `If-Match` and a 412 fails with `CHANGED`. A weak ETag (`W/"..."`) can never satisfy
`If-Match`, which compares strongly, so it is compared with the response's ETag instead.

`openWrite` streams a PUT while the caller writes. A known `contentLength` is sent as
`Content-Length` and must be written exactly, otherwise the upload fails with `OTHER` and is aborted;
without one the body is chunked. `createOnly` sends `If-None-Match: *`, so claiming a path has no
check-then-create race: an existing file fails with `ALREADY_EXISTS` and keeps its content. `close`
ends the body and waits for the response; failures surface from `write` or `close`. `abort` cancels
the call before the body is complete, and `use` aborts an upload whose block throws instead of
committing it. Servers discard an incomplete known-length body; an incomplete chunked body may be
stored by servers that do not insist on the final chunk (see below).

`capacity` reads the RFC 4331 `quota-available-bytes` and `quota-used-bytes` properties. It returns
`null` when the available bytes are missing or negative (Nextcloud reports `-3` for an unlimited
quota); `totalBytes` is known only when the used bytes are reported too.

HTTP statuses map to `WebDavException.Kind`: 401 `AUTHENTICATION`, 403 `ACCESS_DENIED`, 404 and 410
`MISSING`, 405 on MKCOL `ALREADY_EXISTS` and otherwise `UNSUPPORTED` (on PROPFIND: not a WebDAV
endpoint), 409 `CONFLICT`, 412 `ALREADY_EXISTS` for `Overwrite: F` and `If-None-Match: *` and
`CHANGED` for `If-Match`, 413 and 507 `DISK_FULL`, 423 `LOCKED`, 501 `UNSUPPORTED`. Unhandled
redirects, malformed multistatus bodies and bad `Content-Range` headers are `PROTOCOL`; I/O errors,
timeouts and TLS failures other than the two certificate kinds are `TRANSPORT`.

Multistatus bodies are parsed with a namespace-aware SAX parser using only portable hardening:
external entities are disabled and a `LexicalHandler` rejects any DOCTYPE. Implementation-specific
feature names are avoided because Android's Expat-backed parser rejects them.

## Server behavior

Exercised against Nextcloud 35.0.1 (official image, SQLite) and Apache httpd 2.4.69 with
`mod_dav_fs`:

- Apache redirects requests on a collection URL without a trailing slash to the slashed URL, which
  the redirect rules above follow. A MOVE or COPY whose `Destination` names an existing collection
  without a trailing slash gets `301 Destination URI had an error` without a `Location`, which
  fails with `PROTOCOL` rather than `ALREADY_EXISTS`.
- Apache reports a weak ETag for a file modified within the last second and a strong one
  afterwards. Nextcloud keeps a file's ETag when it is overwritten within the same second, so a
  change in that window is not detected.
- Nextcloud answers a PUT into a missing parent with 404 (`MISSING`); Apache and RFC 4918 use 409.
- Nextcloud stores the bytes of a chunked PUT whose connection drops before the final chunk, so an
  aborted chunked upload leaves a partial file there. It rejects a known-length body that ends
  early. Apache discards both.
- Nextcloud answers 416 without `Content-Range` and reports `-3` as available quota when the user
  has no quota. Apache's `mod_dav_fs` reports no quota.
- A `createOnly` PUT on an existing file failed with 412 on both servers, for empty bodies and for
  an 8 MiB body without `Expect: 100-continue`. Over a slower link, a server answering early may
  close the connection while the body is still being sent, which surfaces as `TRANSPORT`.

## Tests

Run from the repository root with its configured JDK:

```sh
./gradlew :lib-webdav:test
./gradlew :lib-webdav:contractTest
```

Unit tests need no server. They run against `FakeWebDavServer`, an in-memory WebDAV server on
OkHttp's MockWebServer in the `testFixtures` source set, and against scripted MockWebServer
responses for protocol edge cases. The fake implements PROPFIND (Depth 0 and 1), GET with `Range`
and `If-Match`, PUT with `If-None-Match: *`, MKCOL, DELETE, MOVE and COPY, Basic auth and TLS
through okhttp-tls `HeldCertificate`s (`TestCertificates`). Switches reproduce server behavior:
href styles, weak ETags, quota values including negative sentinels, ignored ranges, 416 with or
without `Content-Range`, 411 for PUTs without `Content-Length`, Apache-style slash redirects, and a
response transformer for dropped connections or delays. Other modules can depend on it with
`testImplementation(testFixtures(project(":lib-webdav")))`.

`contractTest` requires Docker and runs `WebDavContractTest` (tag `webdav-server`) against two
containers pinned by image digest: Nextcloud (auto-installed on SQLite, endpoint
`/remote.php/dav/files/butler/`, brute-force protection off, 1 GB quota) and stock Apache httpd
with `mod_dav_fs` (`/dav/` with Basic auth, anonymous `/public/`). Where the servers differ, the
subclasses declare the expected outcome. Live contract tests bypass cached/up-to-date results.
