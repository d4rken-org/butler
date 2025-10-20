package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import java.io.File


fun File.toLocalPath(): LocalPath = LocalPath.build(this)
