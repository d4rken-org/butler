package eu.darken.butler.common.compose

import androidx.annotation.RawRes
import eu.darken.butler.common.Occasions
import eu.darken.butler.common.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Seasonal replacements for the drink clips' cup layer.
 *
 * Supply replacement shapes in the target cup layer's coordinate space.
 * The swap preserves that layer's transform and timing.
 */
internal enum class MascotProp(@RawRes val shapes: Int, val steaming: Boolean) {
    PUMPKIN(R.raw.mascot_prop_pumpkin, steaming = true),
    STEIN(R.raw.mascot_prop_stein, steaming = false),
    PINT(R.raw.mascot_prop_pint, steaming = false),
    COCOA(R.raw.mascot_prop_cocoa, steaming = true),
    COUPE(R.raw.mascot_prop_coupe, steaming = false),
    WATERING_CAN(R.raw.mascot_prop_watering_can, steaming = false),
    ;

    companion object {
        val DRINK_CLIPS = setOf(R.raw.mascot_lottie_drink, R.raw.mascot_lottie_drink_standalone)

        const val CUP_LAYER = "Layer 1/cup Outlines"
        val STEAM_LAYERS = setOf("smoke", "smoke 2")

        fun forOccasion(occasion: Occasions.Period): MascotProp? = when (occasion) {
            Occasions.Period.HALLOWEEN -> PUMPKIN
            Occasions.Period.OKTOBERFEST -> STEIN
            Occasions.Period.ST_PATRICKS -> PINT
            Occasions.Period.XMAS -> COCOA
            Occasions.Period.NEW_YEAR -> COUPE
            Occasions.Period.APRIL_FOOLS -> WATERING_CAN
            Occasions.Period.NONE -> null
        }

        /**
         * Replaces the cup layer's shapes in [clip] with [shapes], keeping its transform and timing.
         * Cold props also drop the steam.
         */
        fun swap(clip: String, shapes: String, steaming: Boolean): String {
            val root = Json.parseToJsonElement(clip).jsonObject
            val prop = Json.parseToJsonElement(shapes).jsonArray
            val layers = root.getValue("layers").jsonArray.mapNotNull { element ->
                val layer = element.jsonObject
                when (layer["nm"]?.jsonPrimitive?.content) {
                    CUP_LAYER -> JsonObject(layer + ("shapes" to prop))
                    in STEAM_LAYERS -> layer.takeIf { steaming }
                    else -> layer
                }
            }
            return JsonObject(root + ("layers" to JsonArray(layers))).toString()
        }
    }
}
