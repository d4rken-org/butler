package eu.darken.butler.common.compose

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec.*
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import eu.darken.butler.common.Occasions
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerMascotMode.*
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun resolveHat(hat: ButlerMascotMode.Hat): Int? {
    return when (hat) {
        Hat.NO_HAT -> null
        Hat.PARTY -> R.drawable.mascot_hat_party
        Hat.XMAS -> R.drawable.mascot_hat_xmas
        Hat.HALLOWEEN -> R.drawable.mascot_hat_halloween
        Hat.ST_PATRICKS -> R.drawable.mascot_hat_stpatricks
        Hat.APRIL_FOOLS -> R.drawable.mascot_hat_aprilfools
        Hat.OKTOBERFEST -> R.drawable.mascot_hat_oktoberfest
        Hat.AUTO -> when (Occasions.current()) {
            Occasions.Period.HALLOWEEN -> R.drawable.mascot_hat_halloween
            Occasions.Period.ST_PATRICKS -> R.drawable.mascot_hat_stpatricks
            Occasions.Period.APRIL_FOOLS -> R.drawable.mascot_hat_aprilfools
            Occasions.Period.OKTOBERFEST -> R.drawable.mascot_hat_oktoberfest
            Occasions.Period.XMAS -> R.drawable.mascot_hat_xmas
            Occasions.Period.NEW_YEAR -> R.drawable.mascot_hat_party
            Occasions.Period.NONE -> null
        }
    }
}

// Every Lottie frame costs a full-window redraw, so vote for a lower rate while animating. Honored
// where the panel can drop that far (Pixel 8: 60Hz -> 30Hz), ignored where the current rate is
// already a multiple of it (Galaxy Tab A9+ stays at 90Hz).
private const val MASCOT_FRAME_RATE = 30f

// No touch or key event for this long means nobody is watching the mascot
private val MASCOT_IDLE_AFTER = 30.seconds

private fun Duration.jittered(): Duration = this + Random.nextLong(inWholeMilliseconds + 1).milliseconds

private val randomCyclingSequences: List<List<Int>> = listOf(
    listOf(R.raw.mascot_lottie_wink),
    listOf(R.raw.mascot_lottie_drink_standalone),
    listOf(R.raw.mascot_lottie_moustache_stroke),
    listOf(
        R.raw.mascot_lottie_sleep_sleeping,
        R.raw.mascot_lottie_sleep_snoring,
        R.raw.mascot_lottie_sleep_waking,
    ),
    listOf(R.raw.mascot_lottie_greeting),
    listOf(R.raw.mascot_lottie_hatoff),
)


private suspend fun loadComposition(
    context: Context,
    @androidx.annotation.RawRes resId: Int,
): LottieComposition? = withContext(Dispatchers.Default) {
    LottieCompositionFactory.fromRawResSync(context, resId).value
}

@Composable
fun ButlerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    variant: ButlerMascotMode = Static.Normal(),
) {
    // SD Maid drops by via MascotCameo. The animatable keeps rendering its last composition after
    // animate() returns, so her styling has to key off what is on screen rather than off a flag the
    // cycle clears - otherwise Butler's hat and scaling snap back over her still-visible last frame.
    val animatable = rememberLottieAnimatable()
    var cameoComposition by remember { mutableStateOf<LottieComposition?>(null) }
    val showingCameo = cameoComposition != null && animatable.composition === cameoComposition

    // Butler's hat overlay is positioned for his head in a 512x512 frame, so it sits her visit out.
    val hatDrawable = if (showingCameo) null else resolveHat(variant.hat)

    Box(modifier = modifier) {
        when (variant) {
        is Static -> Image(
            painter = painterResource(
                id = when (variant) {
                    is Static.Normal -> R.drawable.mascot_normal
                    is Static.Happy -> R.drawable.mascot_happy
                    is Static.Sad -> R.drawable.mascot_sad
                    is Static.Ko -> R.drawable.mascot_ko
                }
            ),
            contentDescription = contentDescription ?: stringResource(R.string.butler_mascot_description),
            modifier = Modifier.fillMaxSize(),
        )

        is Animated -> {
            val userActivity = LocalUserActivity.current
            val animatedDescription = contentDescription ?: stringResource(
                if (showingCameo) R.string.butler_mascot_animation_cameo_description else variant.description
            )
            // SD Maid's artwork fills only the middle of its 1080x1920 canvas, so fitting that
            // canvas into Butler's square slot would draw her at half his size. Cropping the empty
            // canvas away instead lands her within a few percent of him.
            val contentScale = if (showingCameo || variant is Animated.Cameo) {
                ContentScale.Crop
            } else {
                ContentScale.Fit
            }
            val semanticsModifier = Modifier
                .fillMaxSize()
                .preferredFrameRate(MASCOT_FRAME_RATE)
                .semantics { this.contentDescription = animatedDescription }

            when (variant) {
                is Animated.RandomCycling -> {
                    val context = LocalContext.current

                    // Butler's clips are pure vector, but SD Maid's is built from raster layers and
                    // loadComposition() drops those - she renders as a lone coffee cup. Composing her
                    // only once the draw is won keeps Butler's clips on the on-demand path.
                    var cameoRequested by remember { mutableStateOf(false) }
                    var cameoSettled by remember { mutableStateOf(false) }
                    if (cameoRequested) {
                        val result = rememberLottieComposition(RawRes(R.raw.mascot_lottie_cameo_sdmaid))
                        LaunchedEffect(result) {
                            // await() also completes on a parse failure, where the value stays null
                            cameoComposition = runCatching { result.await() }.getOrNull()
                            cameoSettled = true
                        }
                    }

                    LaunchedEffect(variant, userActivity) {
                        val isUserActive = userActivity.isActive(MASCOT_IDLE_AFTER)
                        while (currentCoroutineContext().isActive) {
                            // A Lottie frame invalidates the whole Compose view, so an unattended
                            // screen would redraw at panel rate forever. Rest until someone looks.
                            isUserActive.first { it }

                            var animated = false
                            if (MascotCameo.claim()) {
                                cameoRequested = true
                                // Waits for the load to settle either way, so a failed parse falls
                                // through to a Butler clip instead of stalling the cycle forever
                                snapshotFlow { cameoSettled }.first { it }
                                cameoComposition?.let {
                                    animated = true
                                    animatable.animate(
                                        composition = it,
                                        iterations = 1,
                                        speed = variant.speed,
                                    )
                                }
                            }
                            if (!animated) {
                                // Load on demand, one at a time - parsing all upfront saturates the CPU during startup
                                for (resId in randomCyclingSequences.random()) {
                                    val composition = loadComposition(context, resId) ?: continue
                                    animated = true
                                    animatable.animate(
                                        composition = composition,
                                        iterations = 1,
                                        speed = variant.speed,
                                    )
                                }
                            }
                            if (!animated) {
                                delay(1.seconds)
                                continue
                            }
                            if (!variant.loop) break
                            // Jittered so several on-screen mascots don't fall into lockstep
                            delay(variant.loopDelay.jittered())
                        }
                    }

                    if (animatable.composition != null) {
                        LottieAnimation(
                            composition = animatable.composition,
                            progress = { animatable.progress },
                            modifier = semanticsModifier,
                            contentScale = contentScale,
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.mascot_normal),
                            contentDescription = animatedDescription,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                is Animated.Sleep -> when (variant) {
                    is Animated.Sleep.EyesClose -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_sleeping))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                            speed = variant.speed,
                        )
                    }

                    is Animated.Sleep.Snoring -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_snoring))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                            speed = variant.speed,
                        )
                    }

                    is Animated.Sleep.WakeUp -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_waking))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                            speed = variant.speed,
                        )
                    }
                }

                else -> {
                    val composition by rememberLottieComposition(
                        RawRes(
                            when (variant) {
                                is Animated.Wink -> R.raw.mascot_lottie_wink
                                is Animated.Greeting -> R.raw.mascot_lottie_greeting
                                is Animated.Drink -> if (variant.standalone) R.raw.mascot_lottie_drink_standalone else R.raw.mascot_lottie_drink
                                is Animated.HatOff -> R.raw.mascot_lottie_hatoff
                                is Animated.MoustacheStroke -> R.raw.mascot_lottie_moustache_stroke
                                is Animated.Cameo -> R.raw.mascot_lottie_cameo_sdmaid
                                is Animated.Sleep -> error("Handled above")
                                is Animated.RandomCycling -> error("Handled above")
                            }
                        )
                    )

                    LaunchedEffect(composition, variant.loop, variant.loopDelay, variant.speed, userActivity) {
                        composition ?: return@LaunchedEffect
                        if (variant.loop) {
                            val isUserActive = userActivity.isActive(MASCOT_IDLE_AFTER)
                            while (currentCoroutineContext().isActive) {
                                isUserActive.first { it }
                                animatable.animate(
                                    composition = composition,
                                    iterations = 1,
                                    speed = variant.speed,
                                )
                                if (variant.loopDelay > Duration.ZERO) {
                                    delay(variant.loopDelay.inWholeMilliseconds)
                                }
                            }
                        } else {
                            animatable.animate(
                                composition = composition,
                                iterations = 1,
                                speed = variant.speed,
                            )
                        }
                    }

                    // Compositions parse off the main thread, so the animatable has none for the
                    // first frames and LottieAnimation would draw nothing - a hole wherever the
                    // mascot is sized. Hold the neutral pose until it resolves, as RandomCycling does.
                    if (animatable.composition != null) {
                        LottieAnimation(
                            composition = animatable.composition,
                            progress = { animatable.progress },
                            modifier = semanticsModifier,
                            contentScale = contentScale,
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.mascot_normal),
                            contentDescription = animatedDescription,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

        if (hatDrawable != null) {
            Image(
                painter = painterResource(id = hatDrawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

sealed interface ButlerMascotMode {
    enum class Hat {
        AUTO,
        PARTY,
        XMAS,
        HALLOWEEN,
        ST_PATRICKS,
        APRIL_FOOLS,
        OKTOBERFEST,
        NO_HAT,
    }

    val hat: Hat
        get() = Hat.AUTO

    sealed interface Static : ButlerMascotMode {
        data class Normal(override val hat: Hat = Hat.AUTO) : Static
        data class Happy(override val hat: Hat = Hat.AUTO) : Static
        data class Sad(override val hat: Hat = Hat.AUTO) : Static
        data class Ko(override val hat: Hat = Hat.AUTO) : Static
    }

    sealed interface Animated : ButlerMascotMode {
        val loop: Boolean
        val loopDelay: Duration
        val speed: Float
        val description: Int

        data class RandomCycling(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 15.seconds,
            override val speed: Float = 1f,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_random_description
        }

        data class Wink(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 3.seconds,
            override val speed: Float = 1f,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_wink_description
        }

        data class Greeting(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 3.seconds,
            override val speed: Float = 1f,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_greeting_description
        }

        data class HatOff(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 3.seconds,
            override val speed: Float = 1f,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_hatoff_description
        }

        data class Drink(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 3.seconds,
            override val speed: Float = 1f,
            val standalone: Boolean = false,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_drink_description
        }

        data class MoustacheStroke(
            override val loop: Boolean = true,
            override val loopDelay: Duration = 3.seconds,
            override val speed: Float = 1f,
            override val hat: Hat = Hat.AUTO,
        ) : Animated {
            override val description: Int = R.string.butler_mascot_animation_moustache_description
        }

        data class Cameo(
            override val loop: Boolean = false,
            override val loopDelay: Duration = Duration.ZERO,
            override val speed: Float = 1f,
        ) : Animated {
            override val hat: Hat = Hat.NO_HAT
            override val description: Int = R.string.butler_mascot_animation_cameo_description
        }

        sealed interface Sleep : Animated {
            override val loop: Boolean get() = false
            override val loopDelay: Duration get() = Duration.ZERO
            override val speed: Float get() = 1f

            data class EyesClose(override val hat: Hat = Hat.AUTO) : Sleep {
                override val description: Int = R.string.butler_mascot_animation_sleep_closing_description
            }

            data class Snoring(override val hat: Hat = Hat.AUTO) : Sleep {
                override val description: Int = R.string.butler_mascot_animation_sleep_snoring_description
            }

            data class WakeUp(override val hat: Hat = Hat.AUTO) : Sleep {
                override val description: Int = R.string.butler_mascot_animation_sleep_waking_description
            }
        }
    }
}


@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerMascotStaticPreview() {
    Column {
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Normal(hat = Hat.AUTO)
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Happy(hat = Hat.NO_HAT)
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Sad(hat = Hat.PARTY)
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Ko(hat = Hat.XMAS)
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerMascotAnimatedPreview() {
    ButlerMascot(Modifier.size(96.dp), variant = Animated.RandomCycling())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Wink())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Greeting())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Drink())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.MoustacheStroke())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.EyesClose())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.Snoring())
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.WakeUp())
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerMascotCameoPreview() {
    ButlerMascot(Modifier.size(96.dp), variant = Animated.Cameo())
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerMascotOccasionHatsPreview() {
    Column {
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Normal(hat = Hat.HALLOWEEN),
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Normal(hat = Hat.ST_PATRICKS),
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Normal(hat = Hat.APRIL_FOOLS),
        )
        ButlerMascot(
            Modifier.size(96.dp),
            variant = Static.Normal(hat = Hat.OKTOBERFEST),
        )
    }
}
