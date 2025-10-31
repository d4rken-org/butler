package eu.darken.butler.common

import dagger.Reusable
import javax.inject.Inject

interface ApiLevel {
    fun has(level: Int): Boolean
}

@Reusable
class DefaultApiLevel @Inject constructor() : ApiLevel {
    override fun has(level: Int): Boolean = hasApiLevel(level)
}
