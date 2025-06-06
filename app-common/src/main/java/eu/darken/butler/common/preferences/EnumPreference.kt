package eu.darken.butler.common.preferences

import eu.darken.butler.common.ca.CaString

interface EnumPreference<T : Enum<T>> {
    val label: CaString
}