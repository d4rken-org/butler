package eu.darken.butler.common.pkgs.container

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.SharedLibraryInfo
import androidx.appcompat.content.res.AppCompatResources
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caDrawable
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.cache
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.io.R
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.PermissionDetails
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.pkgs.getIcon2
import eu.darken.butler.common.pkgs.getLabel2
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2

data class LibraryPkg(
    private val sharedLibraryInfo: SharedLibraryInfo,
    private val apkPath: APath<*>,
    override val packageInfo: PackageInfo,
    override val userHandle: UserHandle2,
) : SourceAvailable, PermissionDetails {

    override val id: Pkg.Id
        get() {
            val rawId = if (versionCode == -1L) {
                sharedLibraryInfo.name
            } else {
                "${sharedLibraryInfo.name}_${versionCode}"
            }
            return rawId.toPkgId()
        }

    @get:SuppressLint("NewApi", "DEPRECATION")
    override val versionCode: Long
        get() = if (hasApiLevel(28)) {
            sharedLibraryInfo.longVersion
        } else {
            sharedLibraryInfo.version.toLong()
        }

    override val sourceDir: APath<*>
        get() = apkPath

    override val label: CaString = caString { context ->
        context.packageManager.getLabel2(id)
            ?: sharedLibraryInfo.name?.takeIf { it.isNotBlank() }
            ?: id.name
    }.cache()


    override fun <T> tryField(fieldName: String): T? {
        val field = SharedLibraryInfo::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        return field.get(sharedLibraryInfo) as? T
    }

    override val icon: CaDrawable = caDrawable { context ->
        context.packageManager.getIcon2(id)
            ?: AppCompatResources.getDrawable(context, R.drawable.ic_local_library_24)!!
    }.cache()


    override fun toString(): String = "LibraryPkg(packageName=$packageName, path=$apkPath)"
}