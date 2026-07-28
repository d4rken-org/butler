package eu.darken.butler.upgrade

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Flavor-specific entitlement diagnostics for the debug log header.
 *
 * Deliberately separate from [UpgradeRepo]: the recorder must be able to read this without
 * constructing the billing stack. Resolving [UpgradeRepo] on GPlay would build UpgradeRepoGplay ->
 * BillingManager and start its AppScope collectors and connect loop, so simply enabling a debug
 * recording would change when billing initializes. Implementations must stay inert.
 *
 * Consumed as a multibound SET, not a single binding: the recorder lives here in app-common while
 * the implementations live in the flavor source sets of :app, so app-common must not require any
 * particular flavor to have contributed one.
 */
interface UpgradeDiagnostics {

    /** One-line summary for the log header, or null when the flavor has nothing to report. */
    suspend fun debugInfo(): String?
}

@InstallIn(SingletonComponent::class)
@Module
abstract class UpgradeDiagnosticsModule {
    // Declares the set so consumers can inject it even before (or without) a flavor contribution.
    @Multibinds
    abstract fun upgradeDiagnostics(): Set<UpgradeDiagnostics>
}
