package eu.darken.butler.common

fun <T> Collection<T>?.isNotNullOrEmpty() = !isNullOrEmpty()