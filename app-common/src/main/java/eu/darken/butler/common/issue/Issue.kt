package eu.darken.butler.common.issue

import kotlin.uuid.Uuid

interface Issue {
    val id: Id

    data class Id(val id: Uuid = Uuid.Companion.random())

    interface Resolution
}