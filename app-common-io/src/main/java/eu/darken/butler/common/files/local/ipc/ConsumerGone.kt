package eu.darken.butler.common.files.local.ipc

import java.io.IOException

/**
 * Raised by a host-side IPC stream writer when the pipe write fails because the *consumer* went
 * away — the client cancelled its collector (user-cancelled scan, `take(n)`) and closed its end,
 * so there is nobody left to stream to. It is contained (logged, not rethrown) by the writer's
 * `catch`, because rethrowing would fault the helper's unsupervised app scope, whose uncaught
 * handler kills the whole privileged process (surfacing as a service-connection-lost error on the
 * next IPC call). Wrapping ONLY the pipe write — not the marshalling — keeps genuine
 * serialization/upstream failures propagating loudly instead of being silently swallowed.
 */
internal class ConsumerGone(cause: Throwable) : IOException(cause)
