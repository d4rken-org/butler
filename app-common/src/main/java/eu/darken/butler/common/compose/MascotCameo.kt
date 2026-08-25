package eu.darken.butler.common.compose

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * A guest appearance by SD Maid's mascot inside Butler's own mascot animations.
 *
 * Starts disarmed and is only ever armed by [roll], which the app calls once at startup. A cycling
 * mascot then consumes it via [claim]:
 *
 *     app start -> roll()  (1 in [ODDS])   -> armed
 *     next mascot cycle -> claim() == true -> SD Maid plays instead of a Butler clip, armed cleared
 *
 * Drawing once per process rather than once per cycle keeps the odds independent of how many
 * mascots are on screen, and caps the cameo at one sighting per app run. Leaving the roll to an
 * explicit startup call also keeps it out of tests and screenshot renders, which never arm it.
 */
object MascotCameo {

    private const val ODDS = 200

    private val armed = AtomicBoolean(false)

    fun roll() = armed.set(Random.nextInt(ODDS) == 0)

    /** True at most once per [roll], for the mascot that gets there first. */
    fun claim(): Boolean = armed.getAndSet(false)

    /** Arms the cameo without a draw, so it can be seen on demand. */
    fun arm() = armed.set(true)
}
