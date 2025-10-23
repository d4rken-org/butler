package eu.darken.butler.common.files.metadata

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.toOctal
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Permissions(
    val mode: Int
) : Parcelable {
    @IgnoredOnParcel @Transient val octal: String = mode.toOctal()

    /**
     * Returns a human-readable Linux-style permission string (e.g., "rwxr-xr-x").
     * Handles special bits (setuid, setgid, sticky).
     */
    fun toReadableString(): String {
        // Extract permission bits for user, group, and other
        val user = (mode shr 6) and 0b111
        val group = (mode shr 3) and 0b111
        val other = mode and 0b111

        // Extract special permission bits
        val setuid = (mode and 0b100_000_000_000) != 0 // 04000 in octal
        val setgid = (mode and 0b010_000_000_000) != 0 // 02000 in octal
        val sticky = (mode and 0b001_000_000_000) != 0 // 01000 in octal

        fun formatTriplet(bits: Int, executePos: Char? = null): String {
            val r = if (bits and 0b100 != 0) 'r' else '-'
            val w = if (bits and 0b010 != 0) 'w' else '-'
            val x = when {
                executePos != null -> executePos
                bits and 0b001 != 0 -> 'x'
                else -> '-'
            }
            return "$r$w$x"
        }

        // Determine execute position character for user (handle setuid)
        val userX = when {
            setuid && (user and 0b001) != 0 -> 's'
            setuid -> 'S'
            else -> null
        }

        // Determine execute position character for group (handle setgid)
        val groupX = when {
            setgid && (group and 0b001) != 0 -> 's'
            setgid -> 'S'
            else -> null
        }

        // Determine execute position character for other (handle sticky)
        val otherX = when {
            sticky && (other and 0b001) != 0 -> 't'
            sticky -> 'T'
            else -> null
        }

        return "${formatTriplet(user, userX)}${formatTriplet(group, groupX)}${formatTriplet(other, otherX)}"
    }

    constructor(parcel: Parcel) : this(parcel.readInt())

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(mode)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "Permission($octal)"

    companion object CREATOR : Parcelable.Creator<Permissions> {
        override fun createFromParcel(parcel: Parcel): Permissions {
            return Permissions(parcel)
        }

        override fun newArray(size: Int): Array<Permissions?> {
            return arrayOfNulls(size)
        }
    }
}