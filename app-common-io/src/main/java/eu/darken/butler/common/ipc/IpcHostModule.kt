package eu.darken.butler.common.ipc

interface IpcHostModule {

    /**
     * Not all exceptions can be passed through the binder, see `Parcel.writeException(...)`.
     * [UnsupportedOperationException] is one of the types it supports, so it carries the original
     * across encoded in its message; [IpcClientModule] rebuilds it on the other side.
     */
    fun Throwable.wrapToPropagate(): Exception = UnsupportedOperationException(IpcErrorCodec.encode(this))

    /**
     * The host's decoding half. Calls run host-ward, so errors normally travel the other way; the
     * issue callback is the one flow where the client answers a host and can hand back an exception.
     * Null means nothing was encoded, so the caller keeps the raw text.
     */
    fun decodeCallbackError(carrier: String?): Throwable? =
        IpcErrorCodec.decodeIfMarked(carrier, Throwable().stackTrace)
}
