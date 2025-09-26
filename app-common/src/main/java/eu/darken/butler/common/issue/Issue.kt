package eu.darken.butler.common.issue

import eu.darken.butler.common.ca.CaString
import kotlin.uuid.Uuid

interface Issue {
    val id: Id
    val title: CaString
    val description: CaString

    data class Id(
        val id: Uuid = Uuid.random()
    ) {
        val shortTag: String
            get() = id.toString().take(4)
        val longTag: String
            get() = id.toString()

        override fun toString(): String = "Issue.Id($shortTag)"
    }

    interface Resolution
}