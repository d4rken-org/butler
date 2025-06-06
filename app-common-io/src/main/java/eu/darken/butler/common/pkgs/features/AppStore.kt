package eu.darken.butler.common.pkgs.features

import eu.darken.butler.common.pkgs.Pkg

interface AppStore : Pkg {

    val urlGenerator: ((Pkg.Id) -> String)?
        get() = null
}