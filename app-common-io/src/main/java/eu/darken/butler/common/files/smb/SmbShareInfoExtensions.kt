package eu.darken.butler.common.files.smb

import eu.darken.smb.SmbShareInfo

/**
 * Whether a share shows up when browsing a server entry: a plain disk share whose name can be a path
 * segment. Names ending in `$` are hidden the way Windows clients hide them, e.g. `C$` or `ADMIN$`.
 */
fun SmbShareInfo.isBrowsable(): Boolean = type == SmbShareInfo.Type.DISK &&
    !special &&
    !name.endsWith('$') &&
    SmbLocationInput.pathSegmentIssue(name) == null
