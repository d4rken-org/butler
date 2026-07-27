package eu.darken.butler.common.pkgs.features

import android.os.Parcelable
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.user.UserHandle2
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class InstallId(
    val pkgId: Pkg.Id,
    val userHandle: UserHandle2,
) : Parcelable