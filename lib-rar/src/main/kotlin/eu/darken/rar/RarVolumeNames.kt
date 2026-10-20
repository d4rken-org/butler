package eu.darken.rar

/**
 * Volume file names derived from the first volume's name:
 *
 * ```
 * volumeName("a.part1.rar", 1)   == "a.part2.rar"
 * volumeName("a.part01.rar", 9)  == "a.part10.rar"
 * volumeName("a.rar", 1)         == "a.r00"
 * volumeName("a.rar", 101)       == "a.s00"
 * volumeName("a.part2.rar", 1)   == null    // not a first volume
 * ```
 */
public object RarVolumeNames {
    private val PART = Regex("""^(.*\.part)(\d+)(\.rar)$""", RegexOption.IGNORE_CASE)
    private val LEGACY = Regex("""^(.*)\.(rar)$""", RegexOption.IGNORE_CASE)

    public fun volumeName(firstVolumeName: String, index: Int): String? {
        if (index < 0) return null
        PART.matchEntire(firstVolumeName)?.let { match ->
            val (prefix, digits, suffix) = match.destructured
            if (digits.trimStart('0') != "1") return null
            val number = index.toLong() + 1
            return prefix + number.toString().padStart(digits.length, '0') + suffix
        }
        LEGACY.matchEntire(firstVolumeName)?.let { match ->
            val (base, extension) = match.destructured
            if (index == 0) return firstVolumeName
            val sequence = index - 1
            val letter = 'r' + sequence / 100
            if (letter > 'z') return null
            val upper = extension[0].isUpperCase()
            val prefix = if (upper) letter.uppercaseChar() else letter
            return "$base.$prefix${(sequence % 100).toString().padStart(2, '0')}"
        }
        return null
    }
}
