package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialUnavailableException
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import eu.darken.webdav.WebDavException
import eu.darken.webdav.WebDavException.Kind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.uuid.Uuid

class WebDavStatusMapperTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val path = WebDavPath(locationId, listOf("a", "b.txt"))

    private val pinned = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)
    private val presented = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)

    private val location = testWebDavLocation(locationId)
    private val pinnedLocation = testWebDavLocation(
        locationId,
        pinnedCertificate = TrustedCertificate.from(pinned),
        trustRevision = 3,
    )

    private fun apiError(kind: Kind, certificate: ServerCertificate? = null) =
        WebDavException(kind, presentedCertificate = certificate)

    // region connect

    @Test
    fun `transport failures while connecting are unreachable`() {
        WebDavStatusMapper.mapConnect(apiError(Kind.TRANSPORT), location)
            .shouldBeInstanceOf<WebDavUnreachableException>()
        WebDavStatusMapper.mapConnect(UnknownHostException("cloud.example.com"), location)
            .shouldBeInstanceOf<WebDavUnreachableException>()
        WebDavStatusMapper.mapConnect(apiError(Kind.PROTOCOL), location)
            .shouldBeInstanceOf<WebDavUnreachableException>()
    }

    @Test
    fun `a rejected sign-in is an auth failure`() {
        val mapped = WebDavStatusMapper.mapConnect(apiError(Kind.AUTHENTICATION), location)

        mapped.shouldBeInstanceOf<WebDavAuthException>().endpoint shouldBe location.endpointLabel
        mapped.isWebDavSignInFailure() shouldBe true
    }

    @Test
    fun `a denied collection is not a sign-in failure`() {
        val mapped = WebDavStatusMapper.mapConnect(apiError(Kind.ACCESS_DENIED), location)

        mapped.shouldBeInstanceOf<WebDavAccessDeniedException>()
        mapped.isWebDavSignInFailure() shouldBe false
    }

    @Test
    fun `an endpoint that is no collection or no WebDAV says so`() {
        WebDavStatusMapper.mapConnect(apiError(Kind.NOT_DIRECTORY), location)
            .shouldBeInstanceOf<WebDavNotCollectionException>()
        WebDavStatusMapper.mapConnect(apiError(Kind.UNSUPPORTED), location)
            .shouldBeInstanceOf<WebDavNotCollectionException>()
    }

    @Test
    fun `a missing endpoint stays recognisable as missing`() {
        val mapped = WebDavStatusMapper.mapConnect(apiError(Kind.MISSING), location)

        mapped.shouldBeInstanceOf<ReadException>()
        WebDavStatusMapper.isMissing(mapped) shouldBe true
    }

    @Test
    fun `an untrusted certificate without a pin carries no stored certificate`() {
        val mapped = WebDavStatusMapper.mapConnect(apiError(Kind.CERTIFICATE_UNTRUSTED, presented), location)
            .shouldBeInstanceOf<WebDavCertificateException>()

        mapped.presented.sha256Fingerprint shouldBe presented.sha256Fingerprint
        mapped.presented.subject shouldBe presented.subject
        mapped.stored.shouldBeNull()
        mapped.isWebDavSignInFailure() shouldBe false
    }

    @Test
    fun `a changed certificate names the location and the pin it was judged against`() {
        val mapped = WebDavStatusMapper.mapConnect(apiError(Kind.CERTIFICATE_MISMATCH, presented), pinnedLocation)
            .shouldBeInstanceOf<WebDavCertificateException>()

        mapped.locationId shouldBe locationId
        mapped.endpoint shouldBe pinnedLocation.endpointLabel
        mapped.url shouldBe pinnedLocation.url
        mapped.trustRevision shouldBe 3
        mapped.stored!!.sha256Fingerprint shouldBe pinned.sha256Fingerprint
        mapped.presented.toTrustedCertificate() shouldBe TrustedCertificate.from(presented)
    }

    @Test
    fun `an unreadable pin is reported as no pin`() {
        val corrupt = testWebDavLocation(locationId, pinnedCertificate = testCertificate(7))

        WebDavStatusMapper.mapConnect(apiError(Kind.CERTIFICATE_MISMATCH, presented), corrupt)
            .shouldBeInstanceOf<WebDavCertificateException>()
            .stored.shouldBeNull()
    }

    @Test
    fun `a certificate failure without the certificate is unreachable`() {
        WebDavStatusMapper.mapConnect(apiError(Kind.CERTIFICATE_UNTRUSTED), location)
            .shouldBeInstanceOf<WebDavUnreachableException>()
    }

    @Test
    fun `a sign-in failure is found below wrappers`() {
        WriteException("copy failed", path, ReadException(cause = WebDavAuthException("cloud")))
            .isWebDavSignInFailure() shouldBe true
        ReadException(cause = WebDavCredentialUnavailableException(locationId, "gone"))
            .isWebDavSignInFailure() shouldBe true
        ReadException(cause = WebDavUnreachableException("cloud")).isWebDavSignInFailure() shouldBe false
    }

    @Test
    fun `cancellation passes through unmapped`() {
        val cancel = CancellationException("stop")
        WebDavStatusMapper.mapConnect(cancel, location) shouldBe cancel
        WebDavStatusMapper.mapOperation(cancel, path, "lookup", write = false) shouldBe cancel
    }

    @Test
    fun `an already mapped failure is not wrapped again`() {
        val original = WebDavAuthException("cloud")
        WebDavStatusMapper.mapConnect(original, location) shouldBe original
        WebDavStatusMapper.mapOperation(original, path, "lookup", write = false) shouldBe original

        val pro = WebDavProRequiredException()
        WebDavStatusMapper.mapOperation(pro, path, "lookup", write = false) shouldBe pro

        val credential = WebDavCredentialUnavailableException(locationId, "gone")
        WebDavStatusMapper.mapConnect(credential, location) shouldBe credential
    }

    // endregion

    // region operations

    @Test
    fun `a missing path becomes a read failure`() {
        val mapped = WebDavStatusMapper.mapOperation(apiError(Kind.MISSING), path, "lookup", write = false)
        mapped.shouldBeInstanceOf<ReadException>().path shouldBe path
        WebDavStatusMapper.isMissing(mapped) shouldBe true
        WebDavStatusMapper.isMissing(apiError(Kind.ACCESS_DENIED)) shouldBe false
    }

    @Test
    fun `a missing parent is a not-found of the parent`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.CONFLICT), path, "createFile", write = true)
            .shouldBeInstanceOf<ReadException>()
            .path shouldBe path.parent
    }

    @Test
    fun `a name collision becomes PathAlreadyExists`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.ALREADY_EXISTS), path, "move", write = true)
            .shouldBeInstanceOf<PathAlreadyExistsException>()
    }

    @Test
    fun `access denied becomes a permission denial`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.ACCESS_DENIED), path, "delete", write = true)
            .shouldBeInstanceOf<PathPermissionDeniedException>()
            .reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
    }

    @Test
    fun `an expired session is an auth failure`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.AUTHENTICATION), path, "lookup", write = false, location)
            .shouldBeInstanceOf<WebDavAuthException>()
            .isWebDavSignInFailure() shouldBe true
    }

    @Test
    fun `a certificate failure during an operation carries the pin of its location`() {
        WebDavStatusMapper.mapOperation(
            apiError(Kind.CERTIFICATE_MISMATCH, presented),
            path,
            "read",
            write = false,
            location = pinnedLocation,
        ).shouldBeInstanceOf<WebDavCertificateException>()
            .stored!!.sha256Fingerprint shouldBe pinned.sha256Fingerprint

        WebDavStatusMapper.mapOperation(apiError(Kind.CERTIFICATE_MISMATCH, presented), path, "read", write = false)
            .shouldBeInstanceOf<WebDavUnreachableException>()
    }

    @Test
    fun `a full disk is a write failure`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.DISK_FULL), path, "write", write = true)
            .shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `not a directory becomes a read failure`() {
        WebDavStatusMapper.mapOperation(apiError(Kind.NOT_DIRECTORY), path, "listFiles", write = false)
            .shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `other failures follow the operation direction and keep their cause`() {
        listOf(Kind.LOCKED, Kind.CHANGED, Kind.PROTOCOL, Kind.UNSUPPORTED, Kind.TRANSPORT, Kind.OTHER).forEach { kind ->
            val error = apiError(kind)
            WebDavStatusMapper.mapOperation(error, path, "write", write = true)
                .shouldBeInstanceOf<WriteException>()
                .cause shouldBe error
            WebDavStatusMapper.mapOperation(error, path, "read", write = false)
                .shouldBeInstanceOf<ReadException>()
                .cause shouldBe error
            WebDavStatusMapper.kindOf(WebDavStatusMapper.mapOperation(error, path, "read", write = false)) shouldBe kind
        }
    }

    // endregion

    @Test
    fun `transport loss is recognised, a status failure alone is not`() {
        WebDavStatusMapper.isTransportLost(apiError(Kind.TRANSPORT)) shouldBe true
        WebDavStatusMapper.isTransportLost(SocketTimeoutException()) shouldBe true
        WebDavStatusMapper.isTransportLost(apiError(Kind.ACCESS_DENIED)) shouldBe false
        WebDavStatusMapper.isTransportLost(apiError(Kind.OTHER)) shouldBe false
        WebDavStatusMapper.isTransportLost(WebDavUnreachableException("cloud", apiError(Kind.TRANSPORT))) shouldBe false
    }
}
