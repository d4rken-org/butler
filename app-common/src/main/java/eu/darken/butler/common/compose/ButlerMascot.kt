package eu.darken.butler.common.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import eu.darken.butler.common.R
import kotlinx.coroutines.delay
import kotlin.random.Random

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
                ButlerMascotMode.Animated.RandomCycling -> {
                    val winkComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.mascot_lottie_wink))
                    val drinkComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.mascot_lottie_drink))
                    val moustacheComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.mascot_lottie_moustache_stroke))
                    val sleepComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.mascot_lottie_sleep))
                    val greetingComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.mascot_lottie_greeting))

                    val compositions = listOfNotNull(
                        winkComposition,
                        drinkComposition,
                        moustacheComposition,
                        sleepComposition,
                        greetingComposition,
                    )
                    val animatable = rememberLottieAnimatable()

                    LaunchedEffect(compositions) {
                        if (compositions.isEmpty()) return@LaunchedEffect
                        animatable.snapTo(composition = compositions.first(), progress = 0f)
                        while (true) {
                            delay(Random.nextLong(2000, 5000))
                            animatable.animate(composition = compositions.random(), iterations = 1)
                        }
                    }

                    LottieAnimation(
                        composition = animatable.composition,
                        progress = { animatable.progress },
                        modifier = semanticsModifier,
                    )
                }

                else -> {
                    val composition by rememberLottieComposition(
                        LottieCompositionSpec.RawRes(
                            when (variant) {
                                ButlerMascotMode.Animated.Wink -> R.raw.mascot_lottie_wink
                                ButlerMascotMode.Animated.Greeting -> R.raw.mascot_lottie_greeting
                                ButlerMascotMode.Animated.Drink -> R.raw.mascot_lottie_drink
                                ButlerMascotMode.Animated.MoustacheStroke -> R.raw.mascot_lottie_moustache_stroke
                                ButlerMascotMode.Animated.Sleep -> R.raw.mascot_lottie_sleep
                                ButlerMascotMode.Animated.Random -> listOf(
                                    R.raw.mascot_lottie_wink,
                                    R.raw.mascot_lottie_drink,
                                    R.raw.mascot_lottie_moustache_stroke,
                                    R.raw.mascot_lottie_sleep,
                                    R.raw.mascot_lottie_greeting,
                                ).random()
                                ButlerMascotMode.Animated.RandomCycling -> error("Handled above")
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
        data object Random : Animated
        data object RandomCycling : Animated
        data object Wink : Animated
        data object Greeting : Animated
        data object Drink : Animated
        data object MoustacheStroke : Animated
        data object Sleep : Animated
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
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Random)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.RandomCycling)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Wink)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Greeting)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Drink)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.MoustacheStroke)
        ButlerMascot(Modifier.size(96.dp), variant = ButlerMascotMode.Animated.Sleep)
    }
}