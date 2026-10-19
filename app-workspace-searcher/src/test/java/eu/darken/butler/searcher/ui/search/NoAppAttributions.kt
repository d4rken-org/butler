package eu.darken.butler.searcher.ui.search

import eu.darken.butler.workspace.core.appattribution.AppAttributor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/** A relaxed mock's flow completes without emitting, which would stall the state combine. */
internal fun noAppAttributions(): AppAttributor = mockk {
    every { attributionsFor<Any?>(any()) } returns flowOf(emptyMap())
}
