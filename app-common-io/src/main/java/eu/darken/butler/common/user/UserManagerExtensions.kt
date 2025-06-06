package eu.darken.butler.common.user

import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.toPkgId


suspend fun UserManager2.ourInstall() = InstallId(
    pkgId = BuildConfigWrap.APPLICATION_ID.toPkgId(),
    userHandle = currentUser().handle
)