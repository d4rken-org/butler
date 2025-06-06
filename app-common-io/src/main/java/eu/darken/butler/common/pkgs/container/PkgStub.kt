package eu.darken.butler.common.pkgs.container

import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.pkgs.Pkg

data class PkgStub(override val id: Pkg.Id) : Pkg {
    override val label: CaString?
        get() = null
    override val icon: CaDrawable?
        get() = null
}

fun Pkg.Id.toStub(): PkgStub = PkgStub(this)
