package eu.darken.butler.common.pkgs.container

import android.content.pm.PackageInfo
import androidx.appcompat.content.res.AppCompatResources
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caDrawable
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.cache
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.pkgs.getIcon2
import eu.darken.butler.common.pkgs.getLabel2
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2

data class ArchivedPkg(
    override val packageInfo: PackageInfo,
    override val userHandle: UserHandle2,
    override val installerInfo: InstallerInfo,
) : Installed, InstallDetails {

    override val id: Pkg.Id
        get() = packageInfo.packageName.toPkgId()

    override val label: CaString = caString { context ->
        context.packageManager.getLabel2(id) ?: id.name
    }.cache()

    override val icon: CaDrawable = caDrawable { context ->
        context.packageManager.getIcon2(id) ?: AppCompatResources.getDrawable(context, R.drawable.ic_archive_24)!!
    }.cache()

    override val isEnabled: Boolean
        get() = false

    override fun toString(): String = "ArchivedPkg(packageName=$packageName)"
}