package eu.darken.butler.common.coil

import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import coil3.asImage
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.iconRes
import eu.darken.butler.common.pkgs.Pkg

fun ImageRequest.Builder.loadingView(
    imageView: View,
    loadingView: View
) {
    listener(
        onStart = {
            loadingView.isInvisible = false
            imageView.isInvisible = true
        },
        onSuccess = { _, _ ->
            loadingView.isInvisible = true
            imageView.isInvisible = false
        }
    )
}

fun ImageView.loadAppIcon(pkg: Pkg): Disposable? {
    val current = tag as? Pkg
    if (current?.packageName == pkg.packageName) return null
    tag = pkg

    val request = ImageRequest.Builder(context).apply {
        data(pkg)
        target(ImageViewTarget(this@loadAppIcon))
    }.build()

    return context.imageLoader.enqueue(request)
}

fun ImageView.loadFilePreview(
    lookup: APathLookup<*>,
    options: ImageRequest.Builder.(APathLookup<*>) -> Unit = {
        val alt = ContextCompat.getDrawable(context, lookup.fileType.iconRes)!!.asImage()
        fallback(alt)
        error(alt)
    },
): Disposable? {
    val current = tag as? APathLookup<*>
    if (current?.lookedUp == lookup.lookedUp) return null
    tag = lookup

    val request = ImageRequest.Builder(context).apply {
        data(lookup)
        target(ImageViewTarget(this@loadFilePreview))
        options(lookup)
    }.build()

    return context.imageLoader.enqueue(request)
}