package eu.darken.butler.common.pkgs

import eu.darken.butler.common.pkgs.features.Installed

interface PkgDataSource {
    suspend fun getPkgs(): Collection<Installed>
}