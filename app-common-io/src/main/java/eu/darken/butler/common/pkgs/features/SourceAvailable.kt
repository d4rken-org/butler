package eu.darken.butler.common.pkgs.features

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath

interface SourceAvailable : Installed {

    val sourceDir: APath<*>?
        get() = applicationInfo?.sourceDir?.let { LocalPath.build(it) }

    val splitSources: Set<APath<*>>?
        get() = applicationInfo?.splitSourceDirs?.map { LocalPath.build(it) }?.toSet()

}