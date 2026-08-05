package eu.darken.butler.common.compose.tour

/**
 * Implemented by every guided-tour holder (each a singleton `object`). Its purpose is
 * discoverability: "find implementations" of this interface lists the complete catalog of tours.
 *
 * The contract is intentionally just the stable [id]. Building the [TourDefinition] is NOT part of
 * the interface — some tours expose a plain `definition` property, but others take screen-derived
 * arguments, so the builder signature varies per tour.
 */
interface GuidedTour {
    val id: TourId
}
