package eu.darken.butler.main.core.motd

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Reusable
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.Locale
import javax.inject.Inject

@Reusable
class MotdEndpoint @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val baseHttpClient: OkHttpClient,
    private val baseJson: Json,
) {

    var endpointUrlOverride: String? = null

    private val api: MotdApi by lazy {
        Retrofit.Builder().apply {
            baseUrl(endpointUrlOverride ?: "https://api.github.com")
            client(baseHttpClient)
            addConverterFactory(ScalarsConverterFactory.create())
            addConverterFactory(baseJson.asConverterFactory("application/json".toMediaType()))
        }.build().create(MotdApi::class.java)
    }

    suspend fun getMotd(locale: Locale): MotdState? {
        log(TAG, VERBOSE) { "getMotd(locale=$locale)..." }
        return try {
            getMotd(BuildConfigWrap.FLAVOR, BuildConfigWrap.BUILD_TYPE, locale)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                null
            } else {
                log(TAG, ERROR) { "getMotd($locale) error: ${e.asLog()}" }
                throw e
            }
        }
    }

    private suspend fun getMotd(
        flavor: BuildConfigWrap.Flavor,
        buildType: BuildConfigWrap.BuildType,
        locale: Locale,
    ): MotdState? = withContext(dispatcherProvider.IO) {
        log(TAG, VERBOSE) { "getMotd($flavor, $buildType, $locale)..." }

        val branch = when (buildType) {
            BuildConfigWrap.BuildType.DEV -> "motd"
            BuildConfigWrap.BuildType.BETA, BuildConfigWrap.BuildType.RELEASE -> "main"
        }

        val flavorRaw = when (flavor) {
            BuildConfigWrap.Flavor.FOSS -> "foss"
            BuildConfigWrap.Flavor.GPLAY -> "gplay"
            BuildConfigWrap.Flavor.NONE -> throw IllegalArgumentException("flavor can't be NONE")
        }

        val buildRaw = when (buildType) {
            BuildConfigWrap.BuildType.DEV -> "dev"
            BuildConfigWrap.BuildType.BETA -> "beta"
            BuildConfigWrap.BuildType.RELEASE -> "release"
        }

        val motds = api.listMotds(
            path = "motd/$flavorRaw/$buildRaw",
            branch = branch
        ).also { log(TAG, VERBOSE) { "getMotd($branch, $flavorRaw)... $it" } }

        val filteredMotds = motds.filter { it.type == "file" }

        var usedLocale = locale
        var localizedMotd = filteredMotds.singleOrNull { it.name.endsWith("-${locale.language}.json") }

        if (localizedMotd == null) {
            localizedMotd = filteredMotds.singleOrNull { it.name.endsWith("-en.json") }
            usedLocale = Locale.ENGLISH
        }
        if (localizedMotd == null) {
            localizedMotd = filteredMotds.filter { it.name.endsWith(".json") }.minByOrNull { it.name }
            usedLocale = Locale.ENGLISH
        }

        val motd = localizedMotd?.downloadUrl?.let { api.getMotd(it) }

        return@withContext motd?.let {
            MotdState(
                motd = it,
                locale = usedLocale,
            )
        }
    }

    companion object {
        private val TAG = logTag("Motd", "Endpoint")
    }
}