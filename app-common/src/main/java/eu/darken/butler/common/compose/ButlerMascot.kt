package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
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
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec.*
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import eu.darken.butler.common.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

@Composable
fun ButlerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.butler_mascot_description),
    variant: ButlerMascotMode = ButlerMascotMode.Static.Normal,
) {
    when (variant) {
        is ButlerMascotMode.Static -> Image(
            painter = painterResource(
                id = when (variant) {
                    ButlerMascotMode.Static.Normal -> R.drawable.mascot_normal
                    ButlerMascotMode.Static.Happy -> R.drawable.mascot_happy
                    ButlerMascotMode.Static.Sad -> R.drawable.mascot_sad
                    ButlerMascotMode.Static.Ko -> R.drawable.mascot_ko
                }
            ),
            contentDescription = contentDescription,
            modifier = modifier
        )

        is ButlerMascotMode.Animated -> {
            val semanticsModifier = contentDescription?.let {
                modifier.semantics { this.contentDescription = it }
            } ?: modifier

            when (variant) {
                is ButlerMascotMode.Animated.RandomCycling -> {
                    val winkComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_wink))
                    val drinkComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_drink))
                    val moustacheComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_moustache_stroke))
                    val sleepComposition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep))
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
                                sleepComposition,
                                greetingComposition,
                                hatoffComposition
                            )
                        }.first { it.size == 6 }

                        animatable.snapTo(composition = allCompositions.first(), progress = 0f)
                        while (currentCoroutineContext().isActive) {
                            repeat((3..9).random()) {
                                animatable.animate(composition = allCompositions.random(), iterations = 1)
                                delay((3000..9000L).random())
                            }
                        }
                    }

                    LottieAnimation(
                        composition = animatable.composition,
                        progress = { animatable.progress },
                        modifier = semanticsModifier,
                    )
                }

                is ButlerMascotMode.Animated.Sleep -> when (variant) {
                    ButlerMascotMode.Animated.Sleep.EyesClose -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_sleeping))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                        )
                    }
                    ButlerMascotMode.Animated.Sleep.Snoring -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_snoring))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                        )
                    }
                    ButlerMascotMode.Animated.Sleep.WakeUp -> {
                        val composition by rememberLottieComposition(RawRes(R.raw.mascot_lottie_sleep_waking))
                        LottieAnimation(
                            composition = composition,
                            modifier = semanticsModifier,
                            iterations = 1,
                        )
                    }
                }

                else -> {
                    val composition by rememberLottieComposition(
                        RawRes(
                            when (variant) {
                                is ButlerMascotMode.Animated.Wink -> R.raw.mascot_lottie_wink
                                is ButlerMascotMode.Animated.Greeting -> R.raw.mascot_lottie_greeting
                                is ButlerMascotMode.Animated.Drink -> R.raw.mascot_lottie_drink
                                is ButlerMascotMode.Animated.HatOff -> R.raw.mascot_lottie_hatoff
                                is ButlerMascotMode.Animated.MoustacheStroke -> R.raw.mascot_lottie_moustache_stroke
                                is ButlerMascotMode.Animated.Sleep -> error("Handled above")
                                is ButlerMascotMode.Animated.RandomCycling -> error("Handled above")
                            }
                        )
                    )
                    LottieAnimation(
                        composition = composition,
                        modifier = semanticsModifier,
                        iterations = LottieConstants.IterateForever,
                    )
                }
            }
        }
    }
}

sealed interface ButlerMascotMode {
    sealed interface Static : ButlerMascotMode {
        data object Normal : Static
        data object Happy : Static
        data object Sad : Static
        data object Ko : Static
    }

    sealed interface Animated : ButlerMascotMode {
        data object RandomCycling : Animated
        data object Wink : Animated
        data object Greeting : Animated
        data object HatOff : Animated
        data object Drink : Animated
        data object MoustacheStroke : Animated
        sealed interface Sleep : Animated {
            data object EyesClose : Sleep
            data object Snoring : Sleep
            data object WakeUp : Sleep
        }
    }
}


@Preview2
@Composable
private fun ButlerMascotStaticPreview() {
    PreviewWrapper {
        Column {
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Normal)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Happy)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Sad)
            ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Static.Ko)
        }
    }
}

@Preview2
@Composable
private fun ButlerMascotAnimatedPreview() {
    PreviewWrapper {
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.RandomCycling)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Wink)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Greeting)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Drink)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.MoustacheStroke)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Sleep.EyesClose)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Sleep.Snoring)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Sleep.WakeUp)
    }
}