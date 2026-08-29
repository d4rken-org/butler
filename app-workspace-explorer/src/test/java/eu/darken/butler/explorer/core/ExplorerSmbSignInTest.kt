package eu.darken.butler.explorer.core

import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.smb.SmbAuthException
import eu.darken.butler.common.files.smb.SmbShareAccessDeniedException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerSmbSignInTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun state(
        error: Throwable?,
        target: ExplorerNavigation.Target? = ExplorerNavigation.Target.Directory(SmbPath.root(locationId)),
    ) = ExplorerWorkspace.State.Ready(
        currentTarget = target,
        // A failed load publishes the error with no loaded location left
        currentLocation = null,
        errorIncident = error?.let {
            ErrorIncident(
                incidentId = "test",
                occurredAt = Instant.fromEpochMilliseconds(0),
                occurredAtIsApproximate = false,
                error = it,
                context = emptyMap(),
                logFile = null,
            )
        },
    )

    @Test
    fun `an auth failure names the location that has to be signed in to`() {
        state(SmbAuthException("nas.local")).smbSignInLocationId() shouldBe locationId
    }

    @Test
    fun `a wrapped auth failure still names the location`() {
        val wrapped = ReadException("browsing failed", cause = SmbAuthException("nas.local"))
        state(wrapped).smbSignInLocationId() shouldBe locationId
    }

    @Test
    fun `a share permission denial does not ask for a sign-in`() {
        state(SmbShareAccessDeniedException("nas.local", "media")).smbSignInLocationId() shouldBe null
    }

    @Test
    fun `an unrelated failure asks for nothing`() {
        state(ReadException("boom")).smbSignInLocationId() shouldBe null
        state(error = null).smbSignInLocationId() shouldBe null
    }

    @Test
    fun `a failure outside network storage asks for nothing`() {
        val local = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
        state(SmbAuthException("nas.local"), target = local).smbSignInLocationId() shouldBe null
        state(SmbAuthException("nas.local"), target = ExplorerNavigation.Target.Home).smbSignInLocationId() shouldBe null
    }
}
