package eu.darken.butler.common

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

class TypeMissMatchException(private val expected: Any, private val actual: Any) :
    IllegalArgumentException("Type missmatch: Wanted $expected, but got $actual."), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "TypeMissMatchException".toCaString(),
        description = caString { it.getString(R.string.general_error_type_mismatch_msg, expected, actual) }
    )

    companion object {
        fun check(expected: Any, actual: Any) {
            if (expected != actual) throw TypeMissMatchException(expected, actual)
        }
    }
}