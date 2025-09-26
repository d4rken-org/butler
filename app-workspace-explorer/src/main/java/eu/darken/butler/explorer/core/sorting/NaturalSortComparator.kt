package eu.darken.butler.explorer.core.sorting

object NaturalSortComparator : Comparator<String> {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 === s2) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        return compareStringsNaturally(s1, s2)
    }

    private fun compareStringsNaturally(s1: String, s2: String): Int {
        var i1 = 0
        var i2 = 0

        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val numResult = compareNumbers(s1, s2, i1, i2)
                val advance1 = getNumberLength(s1, i1)
                val advance2 = getNumberLength(s2, i2)

                if (numResult != 0) return numResult

                i1 += advance1
                i2 += advance2
            } else {
                val charResult = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (charResult != 0) return charResult

                i1++
                i2++
            }
        }

        return s1.length.compareTo(s2.length)
    }

    private fun compareNumbers(s1: String, s2: String, start1: Int, start2: Int): Int {
        val num1 = extractNumber(s1, start1)
        val num2 = extractNumber(s2, start2)

        return when {
            num1 == null && num2 == null -> 0
            num1 == null -> -1
            num2 == null -> 1
            else -> num1.compareTo(num2)
        }
    }

    private fun extractNumber(s: String, start: Int): Long? {
        if (start >= s.length || !s[start].isDigit()) return null

        var end = start
        while (end < s.length && s[end].isDigit()) {
            end++
        }

        return try {
            s.substring(start, end).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun getNumberLength(s: String, start: Int): Int {
        var length = 0
        var i = start
        while (i < s.length && s[i].isDigit()) {
            length++
            i++
        }
        return length
    }
}