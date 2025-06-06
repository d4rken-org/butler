package eu.darken.butler.common.sharedresource

interface HasSharedResource<T : Any> {
    val sharedResource: SharedResource<T>

    suspend fun <R> useRes(block: suspend (T) -> R): R = sharedResource.useRes(block)
}