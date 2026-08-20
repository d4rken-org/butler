package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo

/**
 * What the Viewer workspace can actually RENDER.
 *
 * This is the single source of truth for "does Butler have a renderer for this?", which decides
 * whether content has to be materialized under a name that renderer recognizes. It is not what
 * decides whether the viewer is offered at all: the viewer takes anything and explains what it
 * cannot render.
 *
 * Archives are deliberately absent. [ViewerWorkspace] classifies them separately, by file name and
 * before every branch here, and offers browsing instead of rendering - claiming them here would
 * only start copying containers into Butler's cache to give them a "better" extension.
 *
 * [ViewerSupportTest] pins it to the classification in [ViewerWorkspace]: every type claimed here
 * has to reach a displayable [ViewerContent], and anything else has to stay unsupported.
 */
object ViewerSupport {

    /**
     * Whether the viewer has a renderer for [mime]. A file can still fail to open afterwards (a
     * truncated image, a PDF the renderer rejects); this only says a renderer exists at all.
     */
    fun canDisplay(mime: MimeInfo): Boolean = when {
        mime.isApk -> true
        mime.isPdf -> true
        mime.isImage -> true
        else -> false
    }

    /**
     * Whether [fileName] already announces the same kind of content as [mime].
     *
     * The viewer classifies by file name ([MimeInfo.fromFileName]), so a name that disagrees with
     * the content sends the file to the wrong renderer: a PDF called `invoice.jpg` would be handed
     * to the image decoder. Content arriving from another app has to be materialized under a name
     * that matches before the viewer sees it.
     */
    fun hasMatchingName(mime: MimeInfo, fileName: String): Boolean {
        val named = MimeInfo.fromFileName(fileName)
        return when {
            mime.isApk -> named.isApk
            mime.isPdf -> named.isPdf
            mime.isImage -> named.isImage
            else -> false
        }
    }
}
