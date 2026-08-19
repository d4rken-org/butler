package eu.darken.butler.viewer.core

import android.content.Context
import eu.darken.butler.common.files.GatewaySwitch
import io.mockk.mockk
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A real [ViewerContentReader] over a mocked gateway.
 *
 * The reader is a thin dispatcher, so tests about gateway behaviour stay tests about gateway
 * behaviour: mocking the reader instead would just assert that the mock was called.
 *
 * The context is a mock rather than a Robolectric one because these are plain JVM tests: it is only
 * ever touched on the streamed branch, and a source built from a gateway path never takes it.
 */
fun readerFor(gatewaySwitch: GatewaySwitch) = ViewerContentReader(
    context = mockk<Context>(relaxed = true),
    dispatcherProvider = TestDispatcherProvider(),
    gatewaySwitch = gatewaySwitch,
)
