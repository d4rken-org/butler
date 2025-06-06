package eu.darken.butler.common.pkgs.container

import android.content.pm.PackageInfo
import androidx.core.content.ContextCompat
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caDrawable
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.cache
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.AKnownPkg
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.PermissionDetails
import eu.darken.butler.common.pkgs.features.PkgInfo
import eu.darken.butler.common.pkgs.getIcon2
import eu.darken.butler.common.pkgs.getLabel2

data class PkgArchive(
    override val id: Pkg.Id,
    override val packageInfo: PackageInfo
) : PkgInfo, PermissionDetails {

    override val label: CaString = caString { context ->
        context.packageManager.getLabel2(id)?.let { return@caString it }

        AKnownPkg.values
            .singleOrNull { it.id == id }
            ?.labelRes
            ?.let { return@caString context.getString(it) }

        id.name
    }.cache()

    override val icon: CaDrawable = caDrawable { context ->
        context.packageManager.getIcon2(id)?.let { return@caDrawable it }

        AKnownPkg.values
            .singleOrNull { it.id == id }
            ?.iconRes
            ?.let { ContextCompat.getDrawable(context, it) }
            ?.let { return@caDrawable it }

        ContextCompat.getDrawable(context, R.drawable.ic_default_app_icon_24)!!
    }.cache()
}