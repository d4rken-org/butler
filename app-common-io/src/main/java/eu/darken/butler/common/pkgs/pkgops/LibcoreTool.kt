package eu.darken.butler.common.pkgs.pkgops

import dagger.Reusable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.inject.Inject

@Reusable
class LibcoreTool @Inject constructor() {

    private data class ReflectionCache(
        val osField: Field,
        val getpwuidMethod: Method,
        val getgrgidMethod: Method,
        val pwNameField: Field,
        val grNameField: Field,
    )

    private val reflectionCache by lazy {
        runCatching {
            val clazz = Class.forName("libcore.io.Libcore")
            val osField = clazz.getDeclaredField("os").apply { isAccessible = true }
            val os = osField[null] ?: return@runCatching null

            val getpwuidMethod = os.javaClass.getMethod("getpwuid", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            val getgrgidMethod = os.javaClass.getMethod("getgrgid", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }

            val dummyPasswd = getpwuidMethod.invoke(os, 0)
            val pwNameField = dummyPasswd?.javaClass?.getDeclaredField("pw_name")?.apply {
                isAccessible = true
            } ?: return@runCatching null

            val dummyGroup = getgrgidMethod.invoke(os, 0)
            val grNameField = dummyGroup?.javaClass?.getDeclaredField("gr_name")?.apply {
                isAccessible = true
            } ?: return@runCatching null

            ReflectionCache(
                osField = osField,
                getpwuidMethod = getpwuidMethod,
                getgrgidMethod = getgrgidMethod,
                pwNameField = pwNameField,
                grNameField = grNameField,
            )
        }.getOrNull()
    }

    fun getNameForUid(uid: Int): String? {
        try {
            val cache = reflectionCache ?: return null
            val os = cache.osField[null] ?: return null

            val passwd = cache.getpwuidMethod.invoke(os, uid) ?: return null
            return cache.pwNameField[passwd] as String
        } catch (e: Exception) {
            log(TAG, VERBOSE) { "getNameForUid($uid) failed: $e" }
            return null
        }
    }

    fun getNameForGid(gid: Int): String? {
        try {
            val cache = reflectionCache ?: return null
            val os = cache.osField[null] ?: return null

            val group = cache.getgrgidMethod.invoke(os, gid) ?: return null
            return cache.grNameField[group] as String
        } catch (e: Exception) {
            log(TAG, VERBOSE) { "getNameForGid($gid) failed: $e" }
            return null
        }
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "FileSystemOps", "LibcoreTool")
    }
}