package eu.darken.butler.main.core.motd

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import kotlin.uuid.Uuid

interface MotdApi {

    @Serializable
    data class DirectoryContent(
        @SerialName("name") val name: String,
        @SerialName("type") val type: String,
        @SerialName("download_url") val downloadUrl: String?,
    )

    @GET("repos/d4rken-org/butler/contents/{path}")
    suspend fun listMotds(
        @Path("path") path: String,
        @Query("ref") branch: String,
    ): List<DirectoryContent>

    @Serializable
    data class Motd(
        @SerialName("id") @Contextual val id: Uuid,
        @SerialName("message") val message: String,
        @SerialName("primaryLink") val primaryLink: String?,
        @SerialName("versionMinimum") val minimumVersion: Long?,
        @SerialName("versionMaximum") val maximumVersion: Long?,
    )

    @GET
    suspend fun getMotd(@Url url: String): Motd

}