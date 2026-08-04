package eu.darken.butler.upgrade.core

import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class UpgradeRepoFoss @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val fossCache: FossCache,
    private val webpageTool: WebpageTool,
) : UpgradeRepo {

    override val storeSite: String = STORE_SITE
    override val upgradeSite: String = UPGRADE_SITE
    override val betaSite: String = BETA_SITE

    private val refreshTrigger = MutableStateFlow(Uuid.random())

    // Written only from the sharing coroutine (single collector) — no synchronization needed.
    // Recorded INSIDE the flatMapLatest block, upstream of its channel buffer: a downstream onEach
    // can still be waiting on a buffered emission when the inner flow throws, and the catch below
    // would then read a stale (null) value and revoke an entitlement we already saw.
    private var lastKnownInfo: Info? = null

    override val upgradeInfo: Flow<UpgradeRepo.Info> = refreshTrigger
        .flatMapLatest {
            fossCache.upgrade.flow
                .map { data ->
                    if (data == null) {
                        Info()
                    } else {
                        Info(
                            isPro = true,
                            upgradedAt = data.upgradedAt,
                            fossUpgradeType = data.upgradeType,
                        )
                    }
                }
                // Same coroutine as the throw below, so the ordering is guaranteed. Only
                // successfully mapped elements pass here — catch emissions go straight downstream
                // and never record themselves as a last known state.
                .onEach { lastKnownInfo = it }
                .catch { e ->
                    // A SharedFlow cannot fail: without this, a thrown cache read dies inside
                    // shareIn's sharing coroutine and every collector hangs forever (VM state stuck
                    // on Loading, checkSponsorReturn suspended mid-unlock). The pre-shareIn shape
                    // was a cold unshared combine, where the same throw killed each collector's own
                    // flow instead — visibly, via the vmScope error handler. Sharing trades that for
                    // a silent hang, which is exactly why the catch is mandatory here. It sits
                    // INSIDE flatMapLatest so the error completes only this inner subscription —
                    // refresh() resubscribes the cache and recovery stays possible. Last-known
                    // preservation: a late read failure must not revoke an entitlement we already
                    // saw; the error rides on the previous Info instead. Contrast: gplay keeps a
                    // retryWhen loop because billing re-settles in-place — the FOSS read is a local
                    // one-shot, and refresh-driven resubscription IS the retry.
                    if (e is CancellationException) throw e
                    log(TAG, WARN) { "upgradeInfo read failed: ${e.asLog()}" }
                    emit((lastKnownInfo ?: Info()).copy(error = e))
                }
        }
        .setupCommonEventHandlers(TAG) { "upgradeInfo" }
        .shareIn(appScope, SharingStarted.WhileSubscribed(3000L, 0L), replay = 1)

    // Synchronous so the caller learns whether the page actually opened: the FOSS unlock heuristic
    // only arms on a successful launch, and a fire-and-forget coroutine can't report that back.
    fun openGithubSponsorsPage(): Boolean {
        log(TAG) { "openGithubSponsorsPage()" }
        return webpageTool.open(upgradeSite)
    }

    /**
     * Create-only-if-absent inside the store transaction: an existing record (and its upgradedAt —
     * the user-visible "supporter since" date) is never replaced. The VM-level isPro guard alone is
     * not race-free: it reads a shareIn replay that can be stale. Note the kept record is still
     * re-encoded through the current schema — decoded fields are preserved exactly.
     *
     * Caveat, verified for this app: the kotlinx `createValue` DOES take an `onErrorFallbackToDefault`
     * flag, and [FossCache] deliberately passes an explicit `false`. So a stored record that fails to
     * decode makes this transaction THROW instead of reading as absent. The persist then fails
     * outright and the caller restores its pending-return marker for a later retry — there is NO
     * clobber path that could replace a supporter's record just because it failed to parse.
     *
     * The accepted cost of that choice: a genuinely corrupt record fails permanently. Every armed
     * resume repeats the same sequence — the read throws, the marker is restored, the error surfaces
     * — instead of quietly healing itself. That is deliberate: an honest repeated signal, nothing
     * destroyed, and recovery stays an explicit user action.
     *
     * @return true if a new record was created, false if an existing record was kept.
     */
    suspend fun persistUpgrade(): Boolean {
        log(TAG) { "persistUpgrade()" }
        val updated = fossCache.upgrade.update { existing ->
            existing ?: FossUpgrade(
                upgradedAt = Clock.System.now(),
                upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
            )
        }
        // A returned transaction proves the store is readable again: revive a possibly error-stuck
        // inner flow so the record propagates to collectors still holding the error replay.
        refresh()
        // Cross-module property (app-common Updated.old): smart cast refused.
        val previous = updated.old
        return if (previous == null) {
            true
        } else {
            log(TAG, WARN) { "persistUpgrade(): Record already exists (upgradedAt=${previous.upgradedAt}), keeping it" }
            false
        }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = Uuid.random()
    }

    data class Info(
        override val isPro: Boolean = false,
        override val upgradedAt: Instant? = null,
        val fossUpgradeType: FossUpgrade.Type? = null,
        override val error: Throwable? = null,
    ) : UpgradeRepo.Info {
        override val type: UpgradeRepo.Type = UpgradeRepo.Type.FOSS

        // The FOSS entitlement is a local cache read — authoritative from the first emission,
        // there is no billing handshake to wait out.
        override val isSettled: Boolean = true
    }

    companion object {
        private const val STORE_SITE = "https://github.com/d4rken-org/butler"
        private const val UPGRADE_SITE = "https://github.com/sponsors/d4rken"
        private const val BETA_SITE = "https://github.com/d4rken-org/butler/releases"
        private val TAG = logTag("Upgrade", "Foss", "Repo")
    }
}