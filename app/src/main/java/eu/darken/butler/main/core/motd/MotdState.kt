package eu.darken.butler.main.core.motd

import android.net.Uri
import eu.darken.butler.common.serialization.LocaleSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.uuid.Uuid

@Serializable
data class MotdState(
    @SerialName("motd") val motd: MotdApi.Motd,
    @SerialName("locale") val locale: @Serializable(with = LocaleSerializer::class) Locale,
) {
    val id: Uuid
        get() = motd.id

    val allowTranslation: Boolean
        get() = Locale.getDefault().language != locale.language

    val translationUrl: String
        get() = "https://translate.google.com/?text=${Uri.encode(motd.message)}&sl=${locale.language}"
}
