package eu.darken.butler.common.review

import android.app.Activity
import android.content.Context
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.throttleLatest
import eu.darken.butler.main.core.release.ReleaseSettings
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class GplayReviewTool @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val settings: ReviewSettings,
    releaseSettings: ReleaseSettings,
    upgradeRepo: UpgradeRepo,
) : ReviewTool {
    private val manager by lazy { ReviewManagerFactory.create(context) }
    private val reviewRefresh = MutableStateFlow(Uuid.random())
    private val gplayReviewState = reviewRefresh
        .map {
            try {
                manager.requestReview().also {
                    log(TAG) { "requestReview(): ${it.desc()}" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to get ReviewInfo: ${e.asLog()}" }
                null
            }
        }
        .replayingShare(appScope)

    override val state: Flow<ReviewTool.State> = combine(
        settings.lastDismissed.flow,
        settings.reviewedAt.flow,
        gplayReviewState,
        upgradeRepo.upgradeInfo,
        releaseSettings.releasePartyAt.flow,
    ) { lastDismissed, reviewedAt, reviewInfo, upgradeInfo, releasePartyAt ->
        val now = Clock.System.now()
        val isSnoozed = (now - (lastDismissed ?: Instant.fromEpochMilliseconds(0))) < 14.days
        val canShow = reviewInfo?.canShow == true
        val hasReviewed = reviewedAt != null

        // Free trial is 14 days, only ask for review after the user has paid something
        val hasPaidForPro = (now - (upgradeInfo.upgradedAt ?: now)) > 21.days

        // User may still be hangover from party, don't ask for review
        val hasRecoveredFromParty = (now - (releasePartyAt ?: now)) > 5.days

        log(TAG) { "State 1: canShow=$canShow, isSnoozed=$isSnoozed ($lastDismissed), reviewedAt=$reviewedAt" }
        log(TAG) { "State 2: hasRecoveredFromParty=$hasRecoveredFromParty, hasPaidForPro=$hasPaidForPro" }

        ReviewTool.State(
            shouldAskForReview = hasRecoveredFromParty && hasPaidForPro && !isSnoozed && !hasReviewed && canShow,
            hasReviewed = hasReviewed,
        )
    }
        .throttleLatest(500.milliseconds)
        .onStart { emit(ReviewTool.State()) }
        .replayingShare(appScope)

    override suspend fun dismiss() {
        log(TAG, INFO) { "dismiss()" }
        settings.lastDismissed.value(Clock.System.now())
    }

    override suspend fun reviewNow(activity: Activity) {
        val reviewInfo = gplayReviewState.first()
        log(TAG, INFO) { "reviewNow($activity, ${reviewInfo?.desc()})" }

        if (reviewInfo == null) {
            log(TAG, WARN) { "ReviewInfo is unavailable" }
            return
        }

        if (!reviewInfo.canShow) {
            log(TAG, ERROR) { "ReviewInfo says we can't show the prompt, how did we get here?" }
            return
        }

        val reviewTime = measureTimeMillis {
            manager.launchReview(activity, reviewInfo)
        }
        log(TAG) { "Review completed after ${reviewTime}ms" }
        reviewRefresh.value = Uuid.random()

        if (reviewTime.milliseconds >= 2.seconds) {
            log(TAG, INFO) { "Marking review as completed" }
            settings.reviewedAt.value(Clock.System.now())
        } else {
            log(TAG, INFO) { "Review was too quick, counting as dismiss" }
            settings.lastDismissed.value(Clock.System.now())
        }
    }

    private val ReviewInfo.canShow: Boolean
        get() = when {
            toString().contains("isNoOp=true") -> false
            else -> true
        }

    private fun ReviewInfo.desc(): String {
        return "ReviewInfo(canShow=$canShow, ${toString()})"
    }

    companion object {
        private val TAG = logTag("Review", "Tool", "Gplay")
    }
}