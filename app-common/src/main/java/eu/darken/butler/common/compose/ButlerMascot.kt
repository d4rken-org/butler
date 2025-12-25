package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec.*
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import eu.darken.butler.common.Occasions
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerMascotMode.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.time.Duration
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

@Composable
fun ButlerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    variant: ButlerMascotMode = Static.Normal(),
) {
    val hatDrawable = resolveHat(variant.hat)

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
            val animatedDescription = contentDescription ?: stringResource(variant.description)
            val semanticsModifier = Modifier.fillMaxSize().semantics { this.contentDescription = animatedDescription }

            when (variant) {
                is Animated.RandomCycling -> {
                    val winkComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_wink))
                    val drinkComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_drink_standalone))
                    val moustacheComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_moustache_stroke))
                    val sleepSleepingComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_sleeping))
                    val sleepSnoringComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_snoring))
                    val sleepWakingComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_waking))
                    val greetingComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_greeting))
                    val hatoffComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_hatoff))

                    val animatable = rememberLottieAnimatable()

                    LaunchedEffect(Unit) {
                        // Wait until all compositions are loaded (state reads must be inside snapshotFlow)
                        val allCompositions = snapshotFlow {
                            listOfNotNull(
                                winkComposition,
                                drinkComposition,
                                moustacheComposition,
                                sleepSleepingComposition,
                                sleepSnoringComposition,
                                sleepWakingComposition,
                                greetingComposition,
                                hatoffComposition,
                            )
                        }.first { it.size == 8 }

                        // Animation sequences - sleep is a 3-part sequence, others are single
                        val sleepSequence = listOf(
                            sleepSleepingComposition!!,
                            sleepSnoringComposition!!,
                            sleepWakingComposition!!,
                        )
                        val animationSequences: List<List<LottieComposition>> = listOf(
                            listOf(winkComposition!!),
                            listOf(drinkComposition!!),
                            listOf(moustacheComposition!!),
                            sleepSequence,
                            listOf(greetingComposition!!),
                            listOf(hatoffComposition!!),
                        )

                        animatable.snapTo(composition = allCompositions.first(), progress = 0f)
                        while (currentCoroutineContext().isActive) {
                            val sequence = animationSequences.random()
                            for (composition in sequence) {
                                animatable.animate(
                                    composition = composition,
                                    iterations = 1,
                                    speed = variant.speed,
                                )
                            }
                        }
                    }

                    if (animatable.composition != null) {
                        LottieAnimation(
                            composition = animatable.composition,
                            progress = { animatable.progress },
                            modifier = semanticsModifier,
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
                                is Animated.Sleep -> error("Handled above")
                                is Animated.RandomCycling -> error("Handled above")
                            }
                        )
                    )

                    val animatable = rememberLottieAnimatable()

                    LaunchedEffect(composition, variant.loop, variant.loopDelay, variant.speed) {
                        composition ?: return@LaunchedEffect
                        if (variant.loop) {
                            while (currentCoroutineContext().isActive) {
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

                    LottieAnimation(
                        composition = animatable.composition,
                        progress = { animatable.progress },
                        modifier = semanticsModifier,
                    )
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
            override val loopDelay: Duration = 1.seconds,
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
@Composable
private fun ButlerMascotStaticPreview() {
    PreviewWrapper {
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
}

@Preview2
@Composable
private fun ButlerMascotAnimatedPreview() {
    PreviewWrapper {
        ButlerMascot(Modifier.size(96.dp), variant = Animated.RandomCycling())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Wink())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Greeting())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Drink())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.MoustacheStroke())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.EyesClose())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.Snoring())
        ButlerMascot(Modifier.size(96.dp), variant = Animated.Sleep.WakeUp())
    }
}

@Preview2
@Composable
private fun ButlerMascotOccasionHatsPreview() {
    PreviewWrapper {
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
}
