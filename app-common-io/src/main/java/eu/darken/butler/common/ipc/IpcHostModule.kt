package eu.darken.butler.common.ipc

interface IpcHostModule {

    /**
     * Not all exceptions can be passed through the binder, see `Parcel.writeException(...)`.
     * [UnsupportedOperationException] is one of the types it supports, so it carries the original
     * across encoded in its message; [IpcClientModule] rebuilds it on the other side.
     */
    fun Throwable.wrapToPropagate(): Exception = UnsupportedOperationException(IpcErrorCodec.encode(this))
}
