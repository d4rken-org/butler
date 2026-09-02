package eu.darken.butler.common

import android.content.Context

fun openPrivacyPolicy(context: Context): Boolean = WebpageTool.open(context, ButlerLinks.PRIVACY_POLICY)
